[CmdletBinding()]
param([ValidatePattern('^[a-z0-9-]{1,24}$')][string]$Id=('local-'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmss')),[ValidateSet('container','native')][string]$Source='container')
$ErrorActionPreference='Stop'
if(-not $IsWindows){throw 'This local backup uses Windows CurrentUser DPAPI; run it in PowerShell 7 on Windows'}
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $taskRoot 'scripts/ProcessEnvironment.ps1')
$taskBase=Join-Path $taskRoot '.data/backups'
$taskFolder=Join-Path $taskBase $Id
$taskResume=@();$taskLock=$null;$taskCreated=$false;$taskSaved=@{}
$taskNativeStopped=$false;$taskNativeMode='normal';$taskBackupError=$null
. (Join-Path $PSScriptRoot 'BackupBudget.ps1')
. (Join-Path $PSScriptRoot 'BackupResume.ps1')
foreach($taskName in @('AWS_ACCESS_KEY_ID','AWS_SECRET_ACCESS_KEY')){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
function Invoke-BackupDocker {param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments);& docker @Arguments;if($LASTEXITCODE -ne 0){throw ('Docker operation failed: '+$Arguments[0])} }
function Snapshot-Volume([string]$Volume,[string]$Project,[string]$Name) {
    $taskOwner=& docker volume inspect --format '{{index .Labels "com.docker.compose.project"}}' $Volume
    if($LASTEXITCODE -ne 0 -or $taskOwner -ne $Project){throw 'Refusing a volume outside the explicitly owned local project'}
    Invoke-BackupDocker @('run','--rm','--network','none','--user','0:0','--entrypoint','tar','--mount',('type=volume,source='+$Volume+',target=/source,readonly'),'--mount',('type=bind,source='+$taskFolder+',target=/backup'),'merchantpay/mysql:v1-local','-C','/source','-cf',('/backup/'+$Name),'.')
}
function Snapshot-NativeDirectory([string]$Directory,[string]$Name) {
    $taskDirectoryPath=[IO.Path]::GetFullPath((Join-Path $taskRoot $Directory))
    if(-not $taskDirectoryPath.StartsWith((Join-Path $taskRoot '.data')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath $taskDirectoryPath -PathType Container)){throw 'Native evidence directory must exist inside this workspace data directory'}
    Invoke-BackupDocker @('run','--rm','--network','none','--user','0:0','--entrypoint','tar','--mount',('type=bind,source='+$taskDirectoryPath+',target=/source,readonly'),'--mount',('type=bind,source='+$taskFolder+',target=/backup'),'merchantpay/mysql:v1-local','-C','/source','-cf',('/backup/'+$Name),'.')
}
Push-Location $taskRoot
try {
    New-Item -ItemType Directory $taskBase -Force | Out-Null
    New-Item -ItemType Directory (Join-Path $taskRoot '.work/release') -Force | Out-Null
    foreach($taskExisting in @($taskFolder,(Join-Path $taskBase ($Id+'.dpapi')),(Join-Path $taskBase ($Id+'.dpapi.pending')),(Join-Path $taskBase ($Id+'.zip')))){if(Test-Path -LiteralPath $taskExisting){throw 'Backup IDs are immutable; choose a new ID'}}
    $taskLock=[IO.File]::Open((Join-Path $taskRoot '.work/performance-baseline.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    New-Item -ItemType Directory $taskFolder | Out-Null
    $taskCreated=$true
    $taskProject=if($Source -eq 'native'){'merchantpay-v1'}else{'merchantpay-container-v1'}
    $taskServices=if($Source -eq 'native'){@('keycloak')}else{@('console','platform','simulator','keycloak')}
    $taskSimulator=if($Source -eq 'native'){'http://127.0.0.1:9091'}else{'http://127.0.0.1:19091'}
    $taskIdentity=if($Source -eq 'native'){'http://127.0.0.1:8180'}else{'http://127.0.0.1:18180'}
    foreach($taskService in @($taskServices)+@('mysql')) {
        $taskContainer=$taskProject+'-'+$taskService+'-1'
        $taskOwned=& docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' $taskContainer
        if($LASTEXITCODE -ne 0 -or $taskOwned -ne $taskProject){throw 'Backup container does not belong to the explicit source project'}
    }
    foreach($taskLine in [IO.File]::ReadAllLines((Join-Path $taskRoot '.env'))){if($taskLine -match '^([A-Z_]+)='){$taskSaved[$Matches[1]]=[Environment]::GetEnvironmentVariable($Matches[1],'Process')}}
    . scripts/Import-LocalEnvironment.ps1
    foreach($taskChannel in @('MOCK_A','MOCK_B')) {
        $taskHealth=Invoke-RestMethod ($taskSimulator+'/channels/'+$taskChannel+'/health') -Headers @{'X-Channel-Token'=$env:CHANNEL_TOKEN} -TimeoutSec 5
        if($taskHealth.pendingCallbacks -ne 0 -or $taskHealth.pendingPayments -ne 0){throw 'Drain simulator work before taking the stopped consistency point'}
    }
    Invoke-RestMethod ($taskIdentity+'/realms/merchantpay/protocol/openid-connect/certs') -TimeoutSec 5 | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $taskFolder 'identity-jwks.json')
    if($Source -eq 'native') {
        $taskOwnedNames=[Collections.Generic.HashSet[string]]::new()
        foreach($taskRecord in Get-Content '.work/local-processes.json' -Raw|ConvertFrom-Json) {
            $taskProcess=Get-Process -Id $taskRecord.pid -ErrorAction SilentlyContinue
            if($taskProcess -and $taskProcess.StartTime.ToUniversalTime().Ticks -eq $taskRecord.startedTicks -and $taskProcess.Path -eq $taskRecord.executable){[void]$taskOwnedNames.Add($taskRecord.name)}
        }
        if(@(@('platform','simulator','console')|Where-Object {-not $taskOwnedNames.Contains($_)}).Count -ne 0){throw 'Native backup requires the three currently running, identity-verified project processes'}
        Assert-NativeBackupLocalObjects $taskProject
        $taskNativeMode=(Get-Content '.work/local-profile.json' -Raw|ConvertFrom-Json).mode
        $taskNativeStopped=$true
        & scripts/stop-local.ps1
    }
    foreach($taskService in $taskServices) {
        $taskContainer=$taskProject+'-'+$taskService+'-1'
        $taskRunning=& docker inspect --format '{{.State.Running}}' $taskContainer
        if($taskRunning -eq 'true'){$taskResume+=@($taskContainer);Invoke-BackupDocker @('stop','-t','30',$taskContainer) | Out-Null}
    }
    $taskUnfinished=& docker exec ($taskProject+'-mysql-1') sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u merchantpay -N -e "SELECT COUNT(*) FROM merchantpay.outbox_tasks WHERE status<>CHAR(68,79,78,69)"'
    if($LASTEXITCODE -ne 0 -or $taskUnfinished -ne '0'){throw 'Stopped platform still has unfinished tasks; no consistent backup was published'}
    # Recheck at the stopped consistency point: a last writer may have published after the live precheck.
    if($Source -eq 'native') {
        Assert-NativeBackupLocalObjects $taskProject
    }
    Invoke-BackupDocker @('exec',($taskProject+'-mysql-1'),'sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u merchantpay --single-transaction --no-tablespaces --set-gtid-purged=OFF --hex-blob merchantpay > /tmp/merchantpay-complete-backup.sql')
    Invoke-BackupDocker @('cp',($taskProject+'-mysql-1:/tmp/merchantpay-complete-backup.sql'),(Join-Path $taskFolder 'mysql.sql'))
    Invoke-BackupDocker @('exec',($taskProject+'-mysql-1'),'rm','/tmp/merchantpay-complete-backup.sql')
    if($Source -eq 'native') {
        Snapshot-NativeDirectory '.data/platform' 'platform-data.tar'
        Snapshot-NativeDirectory '.data/simulator' 'simulator-data.tar'
        Snapshot-Volume ($taskProject+'_keycloak-data') $taskProject 'keycloak-data.tar'
    } else {foreach($taskVolume in @('platform-data','simulator-data','keycloak-data')) {Snapshot-Volume ($taskProject+'_'+$taskVolume) $taskProject ($taskVolume+'.tar')}}
    Copy-Item -LiteralPath '.env' -Destination (Join-Path $taskFolder 'runtime.env')
    $taskRealmFile=if($Source -eq 'native'){'.data/keycloak/realm.json'}else{'.data/container/keycloak/realm.json'}
    $taskCredentialFile=if($Source -eq 'native'){'.data/platform/local-api-credentials.json'}else{'.data/container/api-credentials.json'}
    Copy-Item -LiteralPath $taskRealmFile -Destination (Join-Path $taskFolder 'realm.json')
    Copy-Item -LiteralPath $taskCredentialFile -Destination (Join-Path $taskFolder 'api-credentials.json')
    $taskS3='merchantpay-storage-v1-s3-1'
    $taskIncludesS3=$false
    if($Source -eq 'container') {$taskStorageOwner=& docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' $taskS3 2>$null;$taskIncludesS3=$LASTEXITCODE -eq 0 -and $taskStorageOwner -eq 'merchantpay-storage-v1'}
    if($taskIncludesS3) {
        $taskStorage=@{};foreach($taskLine in Get-Content '.data/storage/.env'){if($taskLine -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$taskStorage[$Matches[1]]=$Matches[2]}}
        $env:AWS_ACCESS_KEY_ID=$taskStorage.S3_ACCESS_KEY;$env:AWS_SECRET_ACCESS_KEY=$taskStorage.S3_SECRET_KEY
        $taskJava=(Get-ChildItem '.tools/java25' -Directory|Select-Object -First 1).FullName
        & (Join-Path $taskJava 'bin/java.exe') -cp ((Get-Content 'platform/target/storage-classpath.txt' -Raw).Trim()) scripts/storage/BackupS3Probe.java
        if($LASTEXITCODE -ne 0){throw 'Recovery object could not be verified before backup'}
        if((& docker inspect --format '{{.State.Running}}' $taskS3) -eq 'true'){$taskResume+=@($taskS3);Invoke-BackupDocker @('stop','-t','30',$taskS3)|Out-Null}
        Snapshot-Volume 'merchantpay-storage-v1_objects' 'merchantpay-storage-v1' 's3-objects.tar'
        Copy-Item -LiteralPath '.data/storage/.env' -Destination (Join-Path $taskFolder 'storage.env')
    }
    $taskFiles=@(Get-ChildItem -LiteralPath $taskFolder -File | ForEach-Object {@{name=$_.Name;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
    $taskTotal=($taskFiles|Measure-Object bytes -Sum).Sum
    @{at=[DateTimeOffset]::UtcNow.ToString('o');project=$taskProject;source=$Source;files=$taskFiles;includesS3=$taskIncludesS3;scope='Stopped explicit source; logical MySQL plus platform evidence, simulator H2, Keycloak H2 and exact runtime secrets';referenceR1Certified=$false} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $taskFolder 'manifest.json')
    $taskZip=Join-Path $taskBase ($Id+'.zip')
    Compress-Archive -Path (Join-Path $taskFolder '*') -DestinationPath $taskZip
    try {
        $taskArchiveBytes=(Get-Item -LiteralPath $taskZip).Length
        if(-not (Test-LocalBackupArchiveBudget $taskTotal $taskArchiveBytes $Source)){throw 'Compressed archive exceeds the bounded DPAPI budget (container 256MiB, native 1GiB); source services will resume'}
        $taskProtected=[Security.Cryptography.ProtectedData]::Protect([IO.File]::ReadAllBytes($taskZip),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
        $taskPending=Join-Path $taskBase ($Id+'.dpapi.pending')
        $taskStream=[IO.File]::Open($taskPending,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try {$taskStream.Write($taskProtected);$taskStream.Flush($true)}finally{$taskStream.Dispose()}
        [IO.File]::Move($taskPending,(Join-Path $taskBase ($Id+'.dpapi')),$false)
    } finally {Remove-Item -LiteralPath $taskZip -Force -ErrorAction SilentlyContinue}
    @{at=[DateTimeOffset]::UtcNow.ToString('o');id=$Id;encryptedBundleSha256=(Get-FileHash (Join-Path $taskBase ($Id+'.dpapi'))).Hash.ToLowerInvariant();fileCount=$taskFiles.Count;plainBytes=$taskTotal;archiveBytes=$taskArchiveBytes;source=$Source;includesS3=$taskIncludesS3;encryption='Windows CurrentUser DPAPI, host/account bound; cross-host key recovery not certified';complete=$true;restoreVerified=$false;referenceR1Certified=$false} | ConvertTo-Json | Set-Content (Join-Path $taskRoot ('.work/release/backup-'+$Id+'.json'))
    Write-Output ('Encrypted backup created: .data/backups/'+$Id+'.dpapi; restore verification is a separate step')
} catch {$taskBackupError=$_.Exception.Message;throw} finally {
    $taskResumeError=$null
    try {
        if($taskNativeStopped -or $taskResume.Count -gt 0){Resume-LocalBackupSource -Source $Source -Containers $taskResume -NativeStopped $taskNativeStopped -NativeMode $taskNativeMode -Root $taskRoot}
    } catch {$taskResumeError=$_.Exception.Message} finally {
        try {
            if($taskCreated){@{at=[DateTimeOffset]::UtcNow.ToString('o');source=$Source;plainBytes=$taskTotal;archiveBytes=$taskArchiveBytes;backupFileCreated=(Test-Path -LiteralPath (Join-Path $taskBase ($Id+'.dpapi')));backupError=$taskBackupError;sourceResumePassed=($null -eq $taskResumeError);sourceResumeError=$taskResumeError}|ConvertTo-Json|Set-Content (Join-Path $taskRoot ('.work/release/backup-'+$Id+'-source.json'))}
        } finally {
            # Only this invocation's immutable, checked staging directory is removed.
            try {if($taskCreated -and (Test-Path -LiteralPath $taskFolder) -and [IO.Path]::GetFullPath($taskFolder).StartsWith([IO.Path]::GetFullPath($taskBase)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){Remove-Item -LiteralPath $taskFolder -Recurse -Force}}finally{
                if($taskLock){$taskLock.Dispose()};Pop-Location
                Restore-ProcessEnvironment $taskSaved
            }
        }
    }
    if($taskResumeError){throw ('Backup source resume failed: '+$taskResumeError+$(if($taskBackupError){'; backup also failed: '+$taskBackupError}))}
}
