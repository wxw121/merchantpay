[CmdletBinding()]
param([ValidatePattern('^[a-z0-9][a-z0-9_.-]{0,60}$')][string]$Tag='v1-local',[ValidateSet('local','s3')][string]$Storage,[switch]$Performance)
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
    . scripts/Import-LocalEnvironment.ps1
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
    if($Performance){$taskCompose+=@('-f','infra/compose.app.performance.yaml')}
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
    if($taskConsole.StatusCode -ne 200 -or $taskSession.StatusCode -ne 401){throw 'Container console or same-origin API smoke failed.'}
    New-Item -ItemType Directory -Path '.work/release' -Force | Out-Null
    $taskImages=(& docker compose @taskCompose images --format json) -join "`n"
    if($LASTEXITCODE -ne 0){throw 'Runtime image catalogue failed; healthy HTTP does not prove image identity'}
    @{at=[DateTimeOffset]::UtcNow.ToString('o');project='merchantpay-container-v1';profile=if($Performance){'performance-d0'}else{'standard'};backend='UP';console=200;anonymousSession=401;images=$taskImages;financialAndOidcFlowsVerified=$false;sharedHost=$true;referenceR1Certified=$false} | ConvertTo-Json -Depth 6 | Set-Content '.work/release/container-runtime.json'
    @{type=$Storage}|ConvertTo-Json|Set-Content $taskStorageRecord
    Write-Output ('Container console http://127.0.0.1:15173; storage='+$Storage+'; native project data was not reused.')
} finally {try {Restore-ProcessEnvironment (@{APP_IMAGE_TAG=$taskOldTag}+$taskSaved);Pop-Location}finally{if($taskWorkloadLock){$taskWorkloadLock.Dispose()}}}
