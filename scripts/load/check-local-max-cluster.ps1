[CmdletBinding()]
param([ValidateSet('Single','Dual')][string]$Mode)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $taskRoot
try {
    $taskEdge='infra/compose.app.local-max-edge.yaml'
    $taskDual='infra/compose.app.local-max-dual.yaml'
    if(-not (Test-Path $taskEdge)){throw 'Missing local-max edge overlay'}
    if($Mode -eq 'Dual' -and -not (Test-Path $taskDual)){throw 'Missing local-max dual overlay'}
    $taskArgs=@('--env-file','.env','--env-file','.data/storage/.env','-p','merchantpay-container-v1','-f','infra/compose.yaml','-f','infra/compose.app.yaml','-f','infra/compose.app.s3.yaml','-f','infra/compose.app.performance.yaml','-f','infra/compose.app.local-max.yaml','-f',$taskEdge)
    if($Mode -eq 'Dual'){$taskArgs+=@('-f',$taskDual)}
    $taskConfig=& docker compose @taskArgs config --format json | ConvertFrom-Json
    if($LASTEXITCODE -ne 0){throw 'Cluster Compose configuration failed'}
    $taskPlatform=$taskConfig.services.platform
    $taskEdgeService=$taskConfig.services.'load-edge'
    if(-not $taskEdgeService -or $taskEdgeService.ports[0].published -ne '18082'){throw 'Cluster edge must publish 18082'}
    if($taskConfig.services.simulator.environment.CALLBACK_URL -ne 'http://load-edge:8080/api/channel/callback'){throw 'Simulator callback must use the cluster edge'}
    if($taskPlatform.image -ne 'merchantpay/platform:local-max-tps'){throw 'Primary image mismatch'}
    if($Mode -eq 'Single'){
        if($taskPlatform.environment.API_MAX_INFLIGHT -ne '128' -or $taskPlatform.environment.FAST_WORKER_CONCURRENCY -ne '96' -or $taskPlatform.environment.DATABASE_POOL_SIZE){throw 'Single platform budgets differ from LocalMax'}
        if($taskConfig.services.'platform-b'){throw 'Single mode unexpectedly defines platform-b'}
    } else {
        $taskSecond=$taskConfig.services.'platform-b'
        if(-not $taskSecond){throw 'Missing second platform'}
        if($taskSecond.image -ne $taskPlatform.image){throw 'Second platform image mismatch'}
        if($taskSecond.networks.PSObject.Properties.Name -notcontains 'object-storage'){throw 'Second platform has no S3 network'}
        if($taskSecond.ports[0].published -ne '18081'){throw 'Second platform must publish 18081'}
        foreach($taskName in @('DATABASE_URL','DATABASE_PASSWORD','MASTER_KEY','CHANNEL_TOKEN','CALLBACK_SECRET','METRICS_PASSWORD','OIDC_CLIENT_SECRET','OIDC_BASE_URL','CHANNEL_BASE_URL','CONSOLE_URL','LOCAL_WEBHOOK','OBJECT_STORAGE_TYPE','S3_ENDPOINT','S3_BUCKET','S3_PATH_STYLE','AWS_REGION','AWS_ACCESS_KEY_ID','AWS_SECRET_ACCESS_KEY')){
            if($taskSecond.environment.$taskName -ne $taskPlatform.environment.$taskName){throw "Second platform $taskName differs from the primary"}
        }
        if($taskSecond.volumes[0].source -ne $taskPlatform.volumes[0].source){throw 'Second platform data volume differs from primary'}
        foreach($taskService in @($taskPlatform,$taskSecond)){
            if($taskService.environment.API_MAX_INFLIGHT -ne '64' -or $taskService.environment.FAST_WORKER_CONCURRENCY -ne '48' -or $taskService.environment.DATABASE_POOL_SIZE -ne '16' -or $taskService.environment.CHANNEL_HTTP_CONNECTIONS_PER_ROUTE -ne '32'){
                throw 'Dual per-instance budgets must be 64/48/16/32'
            }
            if($taskService.cpus -ne 4 -or $taskService.mem_limit -ne 2147483648){throw 'Dual instance CPU or memory limit mismatch'}
        }
    }
    $taskProxy='infra/nginx/local-max-'+$(if($Mode -eq 'Dual'){'dual'}else{'single'})+'.conf'
    if(-not (Test-Path $taskProxy)){throw 'Missing proxy configuration'}
    $taskProxyText=Get-Content $taskProxy -Raw
    if($taskProxyText -notmatch 'proxy_next_upstream\s+off;' -or $taskProxyText -notmatch 'server platform:8080;'){throw 'Proxy safety or primary upstream missing'}
    if($Mode -eq 'Dual' -and $taskProxyText -notmatch 'server platform-b:8080;'){throw 'Proxy second upstream missing'}
    if($Mode -eq 'Single' -and $taskProxyText -match 'platform-b'){throw 'Single proxy references second upstream'}
    Write-Output "$Mode Compose budgets, image, callback routing and proxy safety verified"
} finally {Pop-Location}
