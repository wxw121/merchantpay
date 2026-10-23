$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $taskRoot 'scripts/ProcessEnvironment.ps1')
Push-Location $taskRoot
$taskSaved=@{};foreach($taskName in @('AWS_ACCESS_KEY_ID','AWS_SECRET_ACCESS_KEY','S3_ACCESS_KEY','S3_SECRET_KEY','S3_ENDPOINT','S3_BUCKET','S3_PATH_STYLE')){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
    & scripts/release/build-infrastructure.ps1
    New-Item -ItemType Directory '.data/storage' -Force | Out-Null
    $taskEnv=Join-Path $taskRoot '.data/storage/.env'
    if(-not (Test-Path $taskEnv)) {
        $taskAccess='merchantpay-'+[Guid]::NewGuid().ToString('N')
        $taskSecret=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        @("S3_ACCESS_KEY=$taskAccess","S3_SECRET_KEY=$taskSecret") | Set-Content -Encoding utf8NoBOM $taskEnv
    }
    foreach($taskLine in [IO.File]::ReadAllLines($taskEnv)){if($taskLine -match '^(S3_ACCESS_KEY|S3_SECRET_KEY)=(.+)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
    & docker compose --env-file $taskEnv -f infra/compose.storage.yaml up -d
    if($LASTEXITCODE -ne 0){throw 'Local object storage startup failed'}
    $env:AWS_ACCESS_KEY_ID=$env:S3_ACCESS_KEY;$env:AWS_SECRET_ACCESS_KEY=$env:S3_SECRET_KEY
    $env:S3_ENDPOINT='http://127.0.0.1:19002';$env:S3_BUCKET='merchantpay-v1';$env:S3_PATH_STYLE='true'
    pwsh -NoProfile -Command {& scripts/mvn.ps1 -MavenArguments @('-pl','platform','dependency:build-classpath','-Dmdep.outputFile=target/storage-classpath.txt','-B','-ntp')} *> .work/v1-storage-classpath.log
    if($LASTEXITCODE -ne 0){throw 'S3 bootstrap classpath resolution failed'}
    $taskJava=(Get-ChildItem '.tools/java25' -Directory | Select-Object -First 1).FullName
    $taskCp=(Get-Content 'platform/target/storage-classpath.txt' -Raw).Trim()
    & (Join-Path $taskJava 'bin/java.exe') -cp $taskCp scripts/storage/InitializeS3.java
    if($LASTEXITCODE -ne 0){throw 'S3 bootstrap failed'}
    Write-Output 'Storage endpoint http://127.0.0.1:19002; credentials remain in .data/storage/.env. See object-storage documentation to select S3 for the platform.'
} finally {Restore-ProcessEnvironment $taskSaved;Pop-Location}
