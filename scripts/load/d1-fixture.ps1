[CmdletBinding()]
param([ValidatePattern('^[a-z0-9-]{1,24}$')][string]$Id='d1-local',[ValidateRange(1,1000000)][long]$Payments=1000,[ValidateRange(1,1000)][int]$Merchants=100,[ValidateRange(1,30)][int]$Days=30,[switch]$Full,[switch]$VerifyRecovery)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $taskRoot 'scripts/ProcessEnvironment.ps1')
Push-Location $taskRoot
$taskLock=$null
$taskSaved=@{};foreach($taskName in @('FIXTURE_DATABASE_PASSWORD','FIXTURE_MASTER_KEY','MYSQL_PWD','MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD')){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
    if($Full) {
        $Payments=1000000;$Merchants=1000;$Days=30
        $taskCapacityPath=Join-Path $taskRoot '.work/release/v1-capacity-feasibility.json'
        if(-not (Test-Path $taskCapacityPath)){throw 'Full D1 requires a measured capacity sample including indexes, binary logs and channel files'}
        $taskCapacity=Get-Content $taskCapacityPath -Raw|ConvertFrom-Json
        $taskSettings=Get-Content (Join-Path $env:APPDATA 'Docker/settings-store.json') -Raw|ConvertFrom-Json
        $taskDockerDirectory=$taskSettings.CustomWslDistroDir
        if(-not $taskDockerDirectory -or -not (Test-Path $taskDockerDirectory)){throw 'Cannot verify the actual Docker data drive'}
        if([IO.Path]::GetFullPath($taskDockerDirectory).TrimEnd('\','/') -ne [IO.Path]::GetFullPath($taskCapacity.dockerDataDirectory).TrimEnd('\','/')){throw 'Docker data directory changed; repeat the capacity measurement'}
        $taskDrive=Get-PSDrive -Name ([IO.Path]::GetPathRoot($taskDockerDirectory).TrimEnd('\',':'))
        . scripts/load/CapacityBudget.ps1
        $taskBudget=Get-D1CapacityBudget ($taskDrive.Free+$taskDrive.Used) $taskDrive.Free $taskCapacity.projectedMillionBytes
        @{at=[DateTimeOffset]::UtcNow.ToString('o');requestedFull=$true;dockerDataDirectory=$taskDockerDirectory;budget=$taskBudget;gatePassed=$taskBudget.gatePassed;referenceR1Certified=$false} | ConvertTo-Json -Depth 5 | Set-Content '.work/d1-full-preflight.json'
        if(-not $taskBudget.gatePassed){throw 'Full D1 stopped before writes: dataset, uncompressed archive, isolated restore and 10GiB new-load budget would not retain the formal 30% drive reserve'}
    }
    $taskFolder=Join-Path $taskRoot ('.data/fixtures/'+$Id)
    New-Item -ItemType Directory $taskFolder -Force | Out-Null
    $taskLock=[IO.File]::Open((Join-Path $taskFolder 'fixture.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $taskPrivate=Join-Path $taskFolder '.env'
    if(-not (Test-Path $taskPrivate)) {
        $taskPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
        $taskMaster=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        @("FIXTURE_DATABASE_PASSWORD=$taskPassword","FIXTURE_MASTER_KEY=$taskMaster") | Set-Content -Encoding utf8NoBOM $taskPrivate
    }
    foreach($taskLine in [IO.File]::ReadAllLines($taskPrivate)){if($taskLine -match '^(FIXTURE_DATABASE_PASSWORD|FIXTURE_MASTER_KEY)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
    $taskHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Id))).ToLowerInvariant().Substring(0,8)
    $taskDatabase='merchantpay_fixture_'+$taskHash
    $taskContainer='merchantpay-d1-'+$Id
    $taskExisting=& docker ps -a --filter ('name=^'+$taskContainer+'$') --format '{{.ID}}'
    if($taskExisting) {
        $taskOwner=& docker inspect --format '{{index .Config.Labels "dev.merchantpay.fixture"}}' $taskContainer
        if($taskOwner -ne $Id){throw 'Refusing to reuse a container with different ownership'}
        & docker start $taskContainer | Out-Null
    } else {
        $env:MYSQL_ROOT_PASSWORD=$env:FIXTURE_DATABASE_PASSWORD;$env:MYSQL_PASSWORD=$env:FIXTURE_DATABASE_PASSWORD
        & docker run -d --name $taskContainer --label ('dev.merchantpay.fixture='+$Id) -p 127.0.0.1:13308:3306 --cpus=2 --memory=1g -e MYSQL_ROOT_PASSWORD -e MYSQL_PASSWORD -e MYSQL_USER=fixture -e ('MYSQL_DATABASE='+$taskDatabase) -v ($taskContainer+'-data:/var/lib/mysql') merchantpay/mysql:v1-local --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_bin --innodb-buffer-pool-size=268435456 --innodb-flush-log-at-trx-commit=1 --sync-binlog=1 | Out-Null
        if($LASTEXITCODE -ne 0){throw 'Isolated fixture MySQL startup failed'}
    }
    $env:MYSQL_PWD=$env:FIXTURE_DATABASE_PASSWORD
    $taskReady=$false
    for($taskTry=0;$taskTry -lt 60;$taskTry++) {
        & docker exec $taskContainer sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -h127.0.0.1 -u fixture --connect-timeout=2 -N -e "SELECT 1" "$MYSQL_DATABASE"' 2>$null | Out-Null
        if($LASTEXITCODE -eq 0){$taskReady=$true;break};Start-Sleep -Seconds 1
    }
    if(-not $taskReady){throw 'Fixture MySQL did not become ready'}
    $env:FIXTURE_DATABASE_URL='jdbc:mysql://127.0.0.1:13308/'+$taskDatabase
    $taskJava=(Get-ChildItem '.tools/java25' -Directory | Select-Object -First 1).FullName
    if(-not (Test-Path 'platform/target/storage-classpath.txt')) {
        pwsh -NoProfile -Command {& scripts/mvn.ps1 -MavenArguments @('-pl','platform','dependency:build-classpath','-Dmdep.outputFile=target/storage-classpath.txt','-B','-ntp')} *> .work/v1-fixture-classpath.log
        if($LASTEXITCODE -ne 0){throw 'Fixture classpath failed'}
    }
    $taskRuntime=(Get-Content 'platform/target/storage-classpath.txt' -Raw).Trim()
    $taskH2=(Get-ChildItem '.tools/m2/com/h2database/h2' -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
    $taskCp=(Join-Path $taskRoot 'platform/target/classes')+[IO.Path]::PathSeparator+(Join-Path $taskRoot 'simulator/target/classes')+[IO.Path]::PathSeparator+$taskRuntime+[IO.Path]::PathSeparator+$taskH2
    $taskClasses=Join-Path $taskRoot '.work/fixture-classes';New-Item -ItemType Directory $taskClasses -Force | Out-Null
    & (Join-Path $taskJava 'bin/javac.exe') -cp $taskCp -d $taskClasses tools/fixtures/D1Fixture.java tools/fixtures/D1FixtureTest.java tools/fixtures/D1FixtureRecoveryTest.java
    if($LASTEXITCODE -ne 0){throw 'Fixture compilation failed'}
    & (Join-Path $taskJava 'bin/java.exe') -Xmx1536m -cp ($taskClasses+[IO.Path]::PathSeparator+$taskCp) D1Fixture $taskFolder $Payments $Merchants $Days
    if($LASTEXITCODE -ne 0){throw 'Fixture incomplete; original business keys and durable progress retained for resume'}
    & (Join-Path $taskJava 'bin/java.exe') -cp ($taskClasses+[IO.Path]::PathSeparator+$taskCp) D1FixtureTest
    if($LASTEXITCODE -ne 0){throw 'Fixture scope guards failed'}
    if($VerifyRecovery) {
        & (Join-Path $taskJava 'bin/java.exe') -Xmx512m -cp ($taskClasses+[IO.Path]::PathSeparator+$taskCp) D1FixtureRecoveryTest $taskFolder $Payments $Merchants $Days
        if($LASTEXITCODE -ne 0){throw 'Fixture recovery regression failed'}
    }
    New-Item -ItemType Directory '.work/release' -Force | Out-Null
    Copy-Item (Join-Path $taskFolder 'manifest.json') ('.work/release/fixture-'+$Id+'.json')
    Write-Output 'Fixture and channel authority are isolated. No production database or clock was changed.'
} finally {Restore-ProcessEnvironment $taskSaved;if($taskLock){$taskLock.Dispose()};Pop-Location}
