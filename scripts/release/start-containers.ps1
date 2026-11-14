[CmdletBinding()]
param([ValidatePattern('^[a-z0-9][a-z0-9_.-]{0,60}$')][string]$Tag='v1-local',[ValidateSet('local','s3')][string]$Storage,[switch]$Performance,[switch]$LocalMax)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $taskRoot 'scripts/ProcessEnvironment.ps1')
Push-Location $taskRoot
$taskOldTag=$env:APP_IMAGE_TAG
$taskWorkloadLock=$null
$taskSaved=@{};foreach($taskName in @('S3_ACCESS_KEY','S3_SECRET_KEY')){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
    New-Item -ItemType Directory (Join-Path $taskRoot '.work') -Force|Out-Null
    try {$taskWorkloadLock=[IO.File]::Open((Join-Path $taskRoot '.work/performance-baseline.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}catch [IO.IOException]{throw 'Container startup refused: a workload or maintenance operation holds the workspace lock'}
    & scripts/release/build-infrastructure.ps1
    . scripts/Import-LocalEnvironment.ps1 -EnvironmentFile '.env'
    $taskRealm=[IO.File]::ReadAllText((Join-Path $taskRoot 'infra/keycloak/realm.template.json'))
    $taskRealm=$taskRealm.Replace('__CLIENT_SECRET__',$env:OIDC_CLIENT_SECRET).Replace('__DEMO_PASSWORD__',$env:DEMO_PASSWORD)
    $taskRealm=$taskRealm.Replace('http://127.0.0.1:8080/login/oauth2/code/keycloak','http://127.0.0.1:15173/login/oauth2/code/keycloak').Replace('http://127.0.0.1:5173','http://127.0.0.1:15173')
    $taskFolder=Join-Path $taskRoot '.data/container/keycloak'
    New-Item -ItemType Directory -Path $taskFolder -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $taskFolder 'realm.json'),$taskRealm,[Text.UTF8Encoding]::new($false))
    $env:APP_IMAGE_TAG=$Tag
    $taskCompose=@('--env-file','.env','-p','merchantpay-container-v1','-f','infra/compose.yaml','-f','infra/compose.app.yaml')
    $taskStorageRecord=Join-Path $taskRoot '.data/container/storage-mode.json'
    if(-not $Storage){$Storage=if(Test-Path $taskStorageRecord){(Get-Content $taskStorageRecord -Raw|ConvertFrom-Json).type}else{'local'}}
    if($Storage -notin @('local','s3')){throw 'Unknown persisted storage mode'}
    if($Storage -eq 's3') {
        & scripts/storage/start-local.ps1
        foreach($taskLine in Get-Content '.data/storage/.env'){if($taskLine -match '^(S3_ACCESS_KEY|S3_SECRET_KEY)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
        $taskCompose+=@('-f','infra/compose.app.s3.yaml')
    }
    if($Performance -or $LocalMax){$taskCompose+=@('-f','infra/compose.app.performance.yaml')}
    if($LocalMax){
        & docker image inspect merchantpay/platform:local-max-tps --format '{{.Id}}' | Out-Null
        if($LASTEXITCODE -ne 0){throw 'Build merchantpay/platform:local-max-tps before selecting -LocalMax'}
    }
    $taskExisting=& docker ps -a --filter 'name=^merchantpay-container-v1-mysql-1$' --format '{{.Names}}'
    if($taskExisting){
        $taskRunning=& docker inspect merchantpay-container-v1-mysql-1 --format '{{.State.Running}}'
        if($taskRunning -ne 'true'){
            & docker start merchantpay-container-v1-mysql-1 | Out-Null
            if($LASTEXITCODE -ne 0){throw 'Cannot start existing MySQL for admission-mode preflight'}
            for($taskWait=0;$taskWait -lt 90;$taskWait++){
                if((& docker inspect merchantpay-container-v1-mysql-1 --format '{{.State.Health.Status}}') -eq 'healthy'){break}
                Start-Sleep -Seconds 1
            }
        }
        $taskModeTable=& docker exec merchantpay-container-v1-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u merchantpay -N merchantpay -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=CHAR(112,97,121,109,101,110,116,95,97,100,109,105,115,115,105,111,110,95,109,111,100,101);"'
        if($LASTEXITCODE -ne 0){throw 'Admission-mode preflight could not read MySQL'}
        $taskSlotsActive=0
        if([long]$taskModeTable -eq 1){
            $taskSlotsActive=& docker exec merchantpay-container-v1-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u merchantpay -N merchantpay -e "SELECT slot_mode FROM payment_admission_mode WHERE id=1;"'
            if($LASTEXITCODE -ne 0){throw 'Cannot read persisted admission mode'}
        }
        $taskExistingPlatform=& docker ps -a --filter 'name=^merchantpay-container-v1-platform-1$' --format '{{.Image}}'
        if($LocalMax -or [long]$taskSlotsActive -eq 1 -or $taskExistingPlatform -eq 'merchantpay/platform:local-max-tps'){
            $taskOutstanding=& docker exec merchantpay-container-v1-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u merchantpay -N merchantpay -e "SELECT (SELECT COUNT(*) FROM outbox_tasks WHERE status<>CHAR(68,79,78,69))+(SELECT COUNT(*) FROM payments WHERE status NOT IN (CHAR(83,85,67,67,69,69,68,69,68),CHAR(70,65,73,76,69,68),CHAR(67,76,79,83,69,68)));"'
            if($LASTEXITCODE -ne 0 -or [long]$taskOutstanding -ne 0){throw 'Admission mode changes require an existing deployment to be healthy and drained'}
        }
        if(-not $LocalMax -and [long]$taskSlotsActive -eq 1){
            # Required when restoring an older image which does not know slot mode.
            & docker exec merchantpay-container-v1-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u merchantpay merchantpay -e "START TRANSACTION; SELECT id FROM payment_admission_mode WHERE id=1 FOR UPDATE; UPDATE payment_admission SET inflight=0; UPDATE payment_admission_slots SET payment_id=NULL WHERE payment_id IS NOT NULL; UPDATE payment_admission_mode SET slot_mode=FALSE WHERE id=1; COMMIT;"' | Out-Null
            if($LASTEXITCODE -ne 0){throw 'Could not restore drained counter-mode capacity'}
        }
    }
    if($LocalMax){
        $taskCompose+=@('-f','infra/compose.app.local-max.yaml')
    }
    & docker compose @taskCompose config --quiet
    if($LASTEXITCODE -ne 0){throw 'Container configuration is invalid.'}
    & docker compose @taskCompose up -d mysql keycloak simulator platform console
    if($LASTEXITCODE -ne 0){throw 'Container startup failed.'}
    $taskHealthy=$false
    for($taskAttempt=0;$taskAttempt -lt 90;$taskAttempt++) {
        try {
            if((Invoke-RestMethod 'http://127.0.0.1:18080/actuator/health' -TimeoutSec 2).status -eq 'UP'){$taskHealthy=$true;break}
        } catch {}
        Start-Sleep -Seconds 2
    }
    if(-not $taskHealthy){throw 'Container backend did not become UP. Inspect only this Compose project logs.'}
    $taskConsole=Invoke-WebRequest 'http://127.0.0.1:15173/' -TimeoutSec 10
    $taskSession=Invoke-WebRequest 'http://127.0.0.1:15173/api/console/session' -SkipHttpErrorCheck -TimeoutSec 10
    if($taskConsole.StatusCode -eq 200 -and $taskSession.StatusCode -eq 502){
        # nginx can keep the recreated platform's old container address until it reloads.
        & docker compose @taskCompose restart console
        if($LASTEXITCODE -ne 0){throw 'Could not refresh console backend routing after platform replacement'}
        for($taskAttempt=0;$taskAttempt -lt 20;$taskAttempt++){
            try {
                $taskConsole=Invoke-WebRequest 'http://127.0.0.1:15173/' -TimeoutSec 2
                $taskSession=Invoke-WebRequest 'http://127.0.0.1:15173/api/console/session' -SkipHttpErrorCheck -TimeoutSec 2
                if($taskConsole.StatusCode -eq 200 -and $taskSession.StatusCode -eq 401){break}
            } catch {}
            Start-Sleep -Seconds 1
        }
    }
    if($taskConsole.StatusCode -ne 200 -or $taskSession.StatusCode -ne 401){throw 'Container console or same-origin API smoke failed.'}
    New-Item -ItemType Directory -Path '.work/release' -Force | Out-Null
    $taskImages=(& docker compose @taskCompose images --format json) -join "`n"
    if($LASTEXITCODE -ne 0){throw 'Runtime image catalogue failed; healthy HTTP does not prove image identity'}
    @{at=[DateTimeOffset]::UtcNow.ToString('o');project='merchantpay-container-v1';profile=if($LocalMax){'local-max-2-0'}elseif($Performance){'performance-d0'}else{'standard'};backend='UP';console=200;anonymousSession=401;images=$taskImages;financialAndOidcFlowsVerified=$false;sharedHost=$true;referenceR1Certified=$false} | ConvertTo-Json -Depth 6 | Set-Content '.work/release/container-runtime.json'
    @{type=$Storage}|ConvertTo-Json|Set-Content $taskStorageRecord
    Write-Output ('Container console http://127.0.0.1:15173; storage='+$Storage+'; native project data was not reused.')
} finally {try {Restore-ProcessEnvironment (@{APP_IMAGE_TAG=$taskOldTag}+$taskSaved);Pop-Location}finally{if($taskWorkloadLock){$taskWorkloadLock.Dispose()}}}
