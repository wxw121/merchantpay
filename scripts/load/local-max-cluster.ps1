[CmdletBinding()]
param([ValidateSet('Single','Dual','Restore')][string]$Action)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $taskRoot
$taskLock=$null
try {
    if((Get-Content '.data/container/storage-mode.json' -Raw | ConvertFrom-Json).type -ne 's3'){
        throw 'This local cluster experiment requires the existing S3 storage mode'
    }
    $taskLock=[IO.File]::Open((Join-Path $taskRoot '.work/performance-baseline.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $taskBase=@('--env-file','.env','--env-file','.data/storage/.env','-p','merchantpay-container-v1','-f','infra/compose.yaml','-f','infra/compose.app.yaml','-f','infra/compose.app.s3.yaml','-f','infra/compose.app.performance.yaml','-f','infra/compose.app.local-max.yaml')
    $taskSingle=$taskBase+@('-f','infra/compose.app.local-max-edge.yaml')
    $taskDual=$taskSingle+@('-f','infra/compose.app.local-max-dual.yaml')
    $taskSql="SELECT (SELECT COUNT(*) FROM outbox_tasks WHERE status<>'DONE')+(SELECT COUNT(*) FROM payments WHERE status NOT IN ('SUCCEEDED','FAILED','CLOSED'))+(SELECT COUNT(*) FROM refunds WHERE status NOT IN ('SUCCEEDED','FAILED'))"
    $taskOutstanding=$taskSql | docker exec -i merchantpay-container-v1-mysql-1 sh -lc 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u merchantpay -N merchantpay'
    if($LASTEXITCODE -ne 0 -or [long]$taskOutstanding -ne 0){throw 'Switch requires zero unfinished tasks, payments and refunds'}
    function Wait-Healthy([string]$url) {
        for($taskAttempt=0;$taskAttempt -lt 60;$taskAttempt++){
            try {if((Invoke-RestMethod $url -TimeoutSec 2).status -eq 'UP'){return}}
            catch {}
            Start-Sleep -Seconds 2
        }
        throw "Platform health did not become UP at $url"
    }
    if($Action -eq 'Restore'){
        & docker compose @taskDual stop platform-b load-edge
        if($LASTEXITCODE -ne 0){throw 'Could not stop cluster-only services'}
        & docker compose @taskBase config --quiet
        if($LASTEXITCODE -ne 0){throw 'Original LocalMax Compose config is invalid'}
        & docker compose @taskBase up -d simulator platform console
        if($LASTEXITCODE -ne 0){throw 'Could not restore original LocalMax services'}
    } else {
        & scripts/load/check-local-max-cluster.ps1 -Mode $Action
        if($LASTEXITCODE -ne 0){throw 'Cluster configuration check failed'}
        if($Action -eq 'Single'){
            & docker compose @taskDual stop platform-b
            if($LASTEXITCODE -ne 0){throw 'Could not stop second platform'}
            & docker compose @taskSingle up -d simulator platform
            if($LASTEXITCODE -ne 0){throw 'Could not start single platform'}
            Wait-Healthy 'http://127.0.0.1:18080/actuator/health'
            & docker compose @taskSingle up -d load-edge
            if($LASTEXITCODE -ne 0){throw 'Could not start single-upstream edge'}
        } else {
            & docker compose @taskDual up -d simulator platform platform-b
            if($LASTEXITCODE -ne 0){throw 'Could not start dual platforms'}
            Wait-Healthy 'http://127.0.0.1:18080/actuator/health'
            Wait-Healthy 'http://127.0.0.1:18081/actuator/health'
            & docker compose @taskDual up -d load-edge
            if($LASTEXITCODE -ne 0){throw 'Could not start dual-upstream edge'}
        }
        Wait-Healthy 'http://127.0.0.1:18082/actuator/health'
        $taskExpected=if($Action -eq 'Dual'){2}else{1}
        $taskObserved=[System.Collections.Generic.HashSet[string]]::new()
        for($taskAttempt=0;$taskAttempt -lt 90 -and $taskObserved.Count -lt $taskExpected;$taskAttempt++){
            try {
                $taskUpstream=(Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:18082/actuator/health' -TimeoutSec 5).Headers.'X-Local-Max-Upstream'
                if($taskUpstream){[void]$taskObserved.Add([string]$taskUpstream)}
            } catch {}
            if($taskObserved.Count -lt $taskExpected){Start-Sleep -Seconds 1}
        }
        if($taskObserved.Count -ne $taskExpected){throw "Expected $taskExpected responding upstreams, observed $($taskObserved.Count)"}
        if($Action -eq 'Dual'){
            $taskFirst=docker inspect merchantpay-container-v1-platform-1 --format '{{.Image}}'
            $taskSecond=docker inspect merchantpay-container-v1-platform-b-1 --format '{{.Image}}'
            if($LASTEXITCODE -ne 0 -or $taskFirst -ne $taskSecond){throw 'Dual platform images differ'}
        }
    }
    Wait-Healthy 'http://127.0.0.1:18080/actuator/health'
    $taskSession=(Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:15173/api/console/session' -SkipHttpErrorCheck -TimeoutSec 10).StatusCode
    if($taskSession -eq 502){
        & docker compose @taskBase restart console
        if($LASTEXITCODE -ne 0){throw 'Could not refresh console routing'}
        $taskSession=(Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:15173/api/console/session' -SkipHttpErrorCheck -TimeoutSec 10).StatusCode
    }
    if($taskSession -ne 401){throw "Anonymous console session returned $taskSession, expected 401"}
    if($Action -eq 'Restore'){
        $taskSecondRunning=docker inspect merchantpay-container-v1-platform-b-1 --format '{{.State.Running}}' 2>$null
        if($taskSecondRunning -eq 'true'){throw 'Second platform remains active after restore'}
    }
    Write-Output "$Action cluster switch healthy; console session=401"
} finally {if($taskLock){$taskLock.Dispose()};Pop-Location}
