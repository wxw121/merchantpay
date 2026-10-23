[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]{1,24}$')][string]$Id,[switch]$Resume,[ValidateSet('container','native')][string]$Source='container')
$ErrorActionPreference='Stop'
if(-not $IsWindows){throw 'The DPAPI bundle is bound to the Windows account that created it'}
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $taskRoot 'scripts/ProcessEnvironment.ps1')
$taskFolder=Join-Path $taskRoot ('.data/restores/'+$Id)
$taskProject='merchantpay-restore-'+$Id
$taskCreated=$false;$taskStarted=@();$taskSaved=@{}
foreach($taskName in @('AWS_ACCESS_KEY_ID','AWS_SECRET_ACCESS_KEY','RUSTFS_ACCESS_KEY','RUSTFS_SECRET_KEY','S3_ENDPOINT','S3_BUCKET','RESTORE_HAS_S3')){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
function Invoke-RestoreDocker {param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments);& docker @Arguments;if($LASTEXITCODE -ne 0){throw ('Isolated restore operation failed: '+$Arguments[0])} }
function Start-RestoreContainer([string]$Name,[string[]]$Arguments) {
    $taskFound=& docker ps -a --filter ('name=^'+$Name+'$') --format '{{.ID}}'
    if($taskFound) {
        $taskOwner=& docker inspect --format '{{index .Config.Labels "dev.merchantpay.restore"}}' $Name
        if(-not $Resume -or $taskOwner -ne $Id){throw 'Refusing to reuse a container outside this explicit restore'}
        Invoke-RestoreDocker @('start',$Name)|Out-Null
    } else {Invoke-RestoreDocker @Arguments|Out-Null}
}
Push-Location $taskRoot
try {
    if((Test-Path $taskFolder) -and -not $Resume){throw 'Restore destination already exists; use -Resume to verify its retained isolated copies'}
    $taskExisting=& docker ps -a --filter ('label=dev.merchantpay.restore='+$Id) --format '{{.ID}}'
    if($taskExisting -and -not $Resume){throw 'This restore already owns containers; inspect its retained evidence or explicitly resume'}
    if(-not $Resume) {
    $taskBundle=Join-Path $taskRoot ('.data/backups/'+$Id+'.dpapi')
    $taskInputLimit=if($Source -eq 'native'){1025MB}else{257MB}
    if((Get-Item -LiteralPath $taskBundle).Length -gt $taskInputLimit){throw 'Encrypted bundle exceeds the explicitly selected DPAPI source budget'}
    $taskPlain=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($taskBundle),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
    New-Item -ItemType Directory $taskFolder -Force | Out-Null;$taskCreated=$true
    $taskZip=Join-Path $taskFolder 'bundle.zip';[IO.File]::WriteAllBytes($taskZip,$taskPlain)
    try {Expand-Archive -LiteralPath $taskZip -DestinationPath $taskFolder}finally{Remove-Item -LiteralPath $taskZip -Force}
    }
    $taskManifest=Get-Content (Join-Path $taskFolder 'manifest.json') -Raw | ConvertFrom-Json
    $taskManifestSource=if($taskManifest.source -eq 'native'){'native'}else{'container'}
    if($Source -ne $taskManifestSource){throw 'Selected restore source does not match the encrypted set'}
    foreach($taskFile in $taskManifest.files) {
        if($taskFile.name -notmatch '^[a-z0-9.-]+$'){throw 'Unsafe backup manifest member'}
        $taskTarget=Join-Path $taskFolder $taskFile.name
        if((Get-Item -LiteralPath $taskTarget).Length -ne $taskFile.bytes -or (Get-FileHash -LiteralPath $taskTarget).Hash.ToLowerInvariant() -ne $taskFile.sha256){throw 'Backup member checksum mismatch'}
    }
    foreach($taskLine in [IO.File]::ReadAllLines((Join-Path $taskFolder 'runtime.env'))) {
        if($taskLine -match '^([A-Z_]+)=(.*)$'){$taskName=$Matches[1];if(-not $taskSaved.ContainsKey($taskName)){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')};[Environment]::SetEnvironmentVariable($taskName,$Matches[2],'Process')}
    }
    foreach($taskName in @('MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD','DATABASE_URL')){if(-not $taskSaved.ContainsKey($taskName)){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}}
    $env:MYSQL_PASSWORD=$env:DATABASE_PASSWORD
    $taskDb=$taskProject+'-db'
    Start-RestoreContainer -Name $taskDb -Arguments @('run','-d','--name',$taskDb,'--label',('dev.merchantpay.restore='+$Id),'-p','127.0.0.1:13309:3306','--cpus=2','--memory=768m','-e','MYSQL_ROOT_PASSWORD','-e','MYSQL_PASSWORD','-e','MYSQL_USER=merchantpay','-e','MYSQL_DATABASE=merchantpay','merchantpay/mysql:v1-local','--innodb-flush-log-at-trx-commit=1','--sync-binlog=1')
    $taskStarted+=@($taskDb)
    $taskReady=$false
    for($taskTry=0;$taskTry -lt 60;$taskTry++) {& docker exec $taskDb sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h127.0.0.1 -u merchantpay -N -e "SELECT 1" merchantpay' 2>$null | Out-Null;if($LASTEXITCODE -eq 0){$taskReady=$true;break};Start-Sleep -Seconds 1}
    if(-not $taskReady){throw 'Restored MySQL did not become ready'}
    if(-not $Resume) {
    Invoke-RestoreDocker @('cp',(Join-Path $taskFolder 'mysql.sql'),($taskDb+':/tmp/restore.sql'))
    Invoke-RestoreDocker @('exec',$taskDb,'sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql -u merchantpay merchantpay < /tmp/restore.sql')
    Invoke-RestoreDocker @('exec','--user','0:0',$taskDb,'rm','/tmp/restore.sql')
    foreach($taskName in @('platform','simulator','keycloak')) {
        $taskDestination=Join-Path $taskFolder $taskName;New-Item -ItemType Directory $taskDestination|Out-Null
        Invoke-RestoreDocker @('run','--rm','--network','none','--user','0:0','--entrypoint','tar','--mount',('type=bind,source='+$taskFolder+',target=/backup,readonly'),'--mount',('type=bind,source='+$taskDestination+',target=/restored'),'merchantpay/mysql:v1-local','-C','/restored','-xf',('/backup/'+$taskName+'-data.tar'))
    }
    }
    $taskJava=(Get-ChildItem '.tools/java25' -Directory|Select-Object -First 1).FullName
    $taskRuntime=(Get-Content 'platform/target/storage-classpath.txt' -Raw).Trim()
    $taskH2=(Get-ChildItem '.tools/m2/com/h2database/h2' -Recurse -Filter '*.jar'|Select-Object -First 1).FullName
    $taskCp=(Join-Path $taskRoot 'platform/target/classes')+[IO.Path]::PathSeparator+(Join-Path $taskRoot 'simulator/target/classes')+[IO.Path]::PathSeparator+$taskRuntime+[IO.Path]::PathSeparator+$taskH2
    $taskClasses=Join-Path $taskRoot '.work/restore-classes';New-Item -ItemType Directory $taskClasses -Force|Out-Null
    & (Join-Path $taskJava 'bin/javac.exe') -cp ($taskCp+[IO.Path]::PathSeparator+(Join-Path $taskRoot 'simulator/target/classes')) -d $taskClasses tools/fixtures/D1Fixture.java tools/fixtures/RestoredSetCheck.java
    if($LASTEXITCODE -ne 0){throw 'Restore verifier compilation failed'}
    $env:DATABASE_URL='jdbc:mysql://127.0.0.1:13309/merchantpay?connectionTimeZone=UTC'
    # Start the actual identity server from its stopped H2 copy; no source realm is re-imported.
    $taskKc=$taskProject+'-keycloak'
    Start-RestoreContainer -Name $taskKc -Arguments @('run','-d','--name',$taskKc,'--label',('dev.merchantpay.restore='+$Id),'-p','127.0.0.1:18181:8080','--cpus=2','--memory=768m','--mount',('type=bind,source='+(Join-Path $taskFolder 'keycloak')+',target=/opt/keycloak/data/h2'),'merchantpay/keycloak:v1-local','start-dev','--http-port=8080','--hostname=http://127.0.0.1:18181')
    $taskStarted+=@($taskKc);$taskIdentity=$false
    for($taskTry=0;$taskTry -lt 60;$taskTry++) {try{$taskJwks=Invoke-RestMethod 'http://127.0.0.1:18181/realms/merchantpay/protocol/openid-connect/certs' -TimeoutSec 2;$taskIdentity=$true;break}catch{};Start-Sleep -Seconds 1}
    if(-not $taskIdentity){throw 'Restored identity server did not expose its saved realm'}
    $taskOriginalKeys=(Get-Content (Join-Path $taskFolder 'identity-jwks.json') -Raw|ConvertFrom-Json).keys.kid|Sort-Object
    if((@($taskOriginalKeys) -join ',') -ne (@($taskJwks.keys.kid|Sort-Object) -join ',')){throw 'Restored identity signing keys differ'}
    $taskS3Restored=$false;$env:RESTORE_HAS_S3='false'
    if($taskManifest.includesS3) {
        $taskS3Folder=Join-Path $taskFolder 's3'
        if(-not (Test-Path $taskS3Folder)) {New-Item -ItemType Directory $taskS3Folder|Out-Null
        Invoke-RestoreDocker @('run','--rm','--network','none','--user','0:0','--entrypoint','tar','--mount',('type=bind,source='+$taskFolder+',target=/backup,readonly'),'--mount',('type=bind,source='+$taskS3Folder+',target=/restored'),'merchantpay/mysql:v1-local','-C','/restored','-xf','/backup/s3-objects.tar')
        }
        foreach($taskLine in Get-Content (Join-Path $taskFolder 'storage.env')) {if($taskLine -match '^S3_ACCESS_KEY=(.*)$'){$env:RUSTFS_ACCESS_KEY=$Matches[1]};if($taskLine -match '^S3_SECRET_KEY=(.*)$'){$env:RUSTFS_SECRET_KEY=$Matches[1]}}
        $taskS3=$taskProject+'-s3'
        Start-RestoreContainer -Name $taskS3 -Arguments @('run','-d','--name',$taskS3,'--label',('dev.merchantpay.restore='+$Id),'-p','127.0.0.1:19003:9000','--cpus=1','--memory=512m','-e','RUSTFS_ACCESS_KEY','-e','RUSTFS_SECRET_KEY','--mount',('type=bind,source='+$taskS3Folder+',target=/data'),'merchantpay/storage:v1-local','/data')
        $taskStarted+=@($taskS3)
        $env:AWS_ACCESS_KEY_ID=$env:RUSTFS_ACCESS_KEY;$env:AWS_SECRET_ACCESS_KEY=$env:RUSTFS_SECRET_KEY;$env:S3_ENDPOINT='http://127.0.0.1:19003';$env:S3_BUCKET='merchantpay-v1'
        # Read-only head verifies the restored existing bucket; no create-on-missing fallback.
        & (Join-Path $taskJava 'bin/java.exe') -cp $taskRuntime scripts/storage/VerifyS3.java
        if($LASTEXITCODE -ne 0){throw 'Restored S3 bucket could not be read'};$taskS3Restored=$true;$env:RESTORE_HAS_S3='true'
    }
    & (Join-Path $taskJava 'bin/java.exe') -cp ($taskClasses+[IO.Path]::PathSeparator+$taskCp) RestoredSetCheck $taskFolder (Join-Path $taskRoot ('.work/release/restore-'+$Id+'-facts.json'))
    if($LASTEXITCODE -ne 0){throw 'Restored financial/channel/key verification failed'}
    $taskFacts=Get-Content (Join-Path $taskRoot ('.work/release/restore-'+$Id+'-facts.json')) -Raw|ConvertFrom-Json
    if($taskFacts.truthAndKeyRecoveryPassed -ne $true -or $taskFacts.moneyAndLocalObjectRecoveryPassed -ne $true){throw 'No positive key/channel/money/object recovery evidence'}
    $taskSourceScope=if($taskManifest.source -eq 'native'){'Native historical development data, not full D1'}else{'Independent container development data, not full D1'}
    @{source=$taskManifest.source;at=[DateTimeOffset]::UtcNow.ToString('o');backupId=$Id;allArchiveHashesVerified=$true;mysqlRestored=$true;channelAndMerchantKeysVerified=$true;identitySigningKeysPreserved=$true;s3Restored=$taskS3Restored;complete=$true;referenceR1Certified=$false;limits=@($taskSourceScope,'DPAPI depends on this Windows user and host','No cross-host RPO/RTO or restored application traffic certification')}|ConvertTo-Json -Depth 5|Set-Content (Join-Path $taskRoot ('.work/release/restore-'+$Id+'.json'))
    Write-Output 'Encrypted set restored and verified in isolated data directories/ports; source services were not replaced.'
} finally {
    if($taskStarted.Count -gt 0){& docker stop -t 15 @taskStarted | Out-Null}
    Restore-ProcessEnvironment $taskSaved
    Pop-Location
}
