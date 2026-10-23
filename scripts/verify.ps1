[CmdletBinding()]
param([switch]$Browser,[switch]$Scale,[switch]$Release)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $taskRoot 'scripts/ProcessEnvironment.ps1')
Push-Location $taskRoot
$taskS3Saved=@{};foreach($taskName in @('S3_TEST_ACCESS_KEY','S3_TEST_SECRET_KEY')){$taskS3Saved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
    & scripts/release/build-infrastructure.ps1
    $taskBackendArgs=@('test','fmt:check','-B','-ntp')
    if($Release) {
        $Browser=$true;$Scale=$true
        & pwsh -NoProfile -File scripts/storage/start-local.ps1
        if($LASTEXITCODE -ne 0){throw 'S3 startup failed'}
        $taskStorage=@{};foreach($taskLine in Get-Content '.data/storage/.env'){if($taskLine -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$taskStorage[$Matches[1]]=$Matches[2]}}
        $env:S3_TEST_ACCESS_KEY=$taskStorage.S3_ACCESS_KEY;$env:S3_TEST_SECRET_KEY=$taskStorage.S3_SECRET_KEY
        $taskBackendArgs+='-Dmerchantpay.test.s3-endpoint=http://127.0.0.1:19002'
    }
    & scripts/mvn.ps1 -MavenArguments $taskBackendArgs
    if($LASTEXITCODE -ne 0) { throw 'Backend verification failed.' }
    & scripts/load/test-local.ps1
    if($LASTEXITCODE -ne 0) { throw 'Load measurement verification failed.' }
    & scripts/load/test-baseline.ps1
    & scripts/load/test-v1.ps1
    & scripts/load/test-capacity.ps1
    & scripts/release/test-scan-gates.ps1
    & scripts/release/test-backup-resume.ps1
    & scripts/release/test-restored-api.ps1
    & scripts/release/test-startup-lock.ps1
    & scripts/test-process-environment.ps1
    & scripts/pnpm.ps1 -PnpmArguments @('--dir','../sdk','verify')
    if($LASTEXITCODE -ne 0){throw 'Generated API client verification failed'}
    & scripts/pnpm.ps1 -PnpmArguments @('test')
    if($LASTEXITCODE -ne 0) { throw 'Frontend tests failed.' }
    & scripts/pnpm.ps1 -PnpmArguments @('build')
    if($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    if($Browser) {
        if($Scale) { $env:RUN_SCALE='1' }
        try { & scripts/pnpm.ps1 -PnpmArguments @('exec','playwright','test'); if($LASTEXITCODE -ne 0) { throw 'Browser verification failed.' } }
        finally { Remove-Item Env:RUN_SCALE -ErrorAction SilentlyContinue }
    }
    if(Test-Path -LiteralPath '.env') {
        & docker compose --env-file .env -f infra/compose.yaml config --quiet
        if($LASTEXITCODE -ne 0) { throw 'Compose configuration failed.' }
    }
    if($Release) {
        & scripts/release/scan-images.ps1
        if($LASTEXITCODE -ne 0){throw 'Runtime image scan failed'}
        & scripts/release/catalog-configured-images.ps1
        & scripts/release/scan-images.ps1 -Manifest .work/release/configured-infrastructure-images.json
        if($LASTEXITCODE -ne 0){throw 'Infrastructure image scan failed'}
    }
    Write-Output 'Requested checks passed. This does not certify R1 performance or D1 capacity.'
} finally {Restore-ProcessEnvironment $taskS3Saved;Pop-Location }
