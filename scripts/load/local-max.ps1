[CmdletBinding()]
param(
    [ValidateSet('Build','Start','Measure','Restore')][string]$Action='Measure',
    [ValidateSet('C1','C2')][string]$Protocol='C1',
    [ValidateRange(10,300)][int]$Seconds=60,
    [ValidateRange(0,10000)][int]$PayRate=310,
    [ValidateRange(0,10000)][int]$RefundRate=32,
    [ValidateRange(0,10000)][int]$QueryRate=0
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $taskRoot
try {
    if($Action -eq 'Build'){
        & pwsh -NoProfile -Command { & scripts/mvn.ps1 -MavenArguments @('clean','package','-DskipTests','-B','-ntp') }
        if($LASTEXITCODE -ne 0){throw 'Clean backend package failed'}
        $taskJava=(Get-ChildItem '.tools/java25' -Directory | Select-Object -First 1).FullName
        $taskEntries=& (Join-Path $taskJava 'bin/jar.exe') tf platform/target/platform-1.0.0-SNAPSHOT.jar
        if($LASTEXITCODE -ne 0){throw 'Cannot inspect packaged migrations'}
        $taskVersions=@($taskEntries | Where-Object {$_ -match '^BOOT-INF/classes/db/migration/V([0-9]+)__'} | ForEach-Object { [regex]::Match($_,'/V([0-9]+)__').Groups[1].Value })
        if(@($taskVersions | Group-Object | Where-Object Count -gt 1).Count -gt 0){throw 'Duplicate packaged migration version'}
        & docker build --pull=false --network=none -t merchantpay/platform:local-max-tps platform
        if($LASTEXITCODE -ne 0){throw 'LocalMax image build failed'}
        return
    }
    if($Action -eq 'Start'){
        & scripts/release/start-containers.ps1 -LocalMax
        return
    }
    if($Action -eq 'Restore'){
        # Keep all volumes and use the ordinary measured 1/1 profile and original local images.
        & scripts/release/start-containers.ps1 -Performance
        return
    }
    $taskStamp=[DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfff')
    New-Item -ItemType Directory -Path '.work/local-max-tps' -Force|Out-Null
    $taskRuntime=@()
    foreach($taskContainer in @('platform','mysql','simulator')){
        $taskInstance=(& docker inspect ('merchantpay-container-v1-'+$taskContainer+'-1') | ConvertFrom-Json)[0]
        if($LASTEXITCODE -ne 0){throw 'Cannot identify benchmark containers'}
        $taskRuntime+=@{service=$taskContainer;image=$taskInstance.Image;cpus=$taskInstance.HostConfig.NanoCpus/1e9;memoryBytes=$taskInstance.HostConfig.Memory;settings=@($taskInstance.Config.Env|Where-Object {$_ -match '^(API_MAX_|FAST_WORKER_|CHANNEL_RESULT_|PAYMENT_ADMISSION_|NOTIFICATION_WRITE_|JAVA_TOOL_OPTIONS=)'})}
    }
    $taskDurability=& docker exec merchantpay-container-v1-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u merchantpay -N merchantpay -e "SELECT @@innodb_flush_log_at_trx_commit,@@sync_binlog,@@log_bin,@@innodb_buffer_pool_size;"'
    if($LASTEXITCODE -ne 0){throw 'Cannot read actual benchmark durability'}
    @{at=[DateTimeOffset]::UtcNow.ToString('o');containers=$taskRuntime;mysqlDurabilityAndBuffer=$taskDurability}|ConvertTo-Json -Depth 6|Set-Content ('.work/local-max-tps/runtime-'+$taskStamp+'.json')
    $taskBefore=@(Get-ChildItem '.work/acceptance' -Directory -Filter 'accept-*' | ForEach-Object Name)
    $taskFailure=$null
    try {
        & scripts/load/run-v1.ps1 -Protocol $Protocol -Scaled -Seconds $Seconds -PayRate $PayRate -RefundRate $RefundRate -QueryRate $QueryRate -CredentialsFile '.data/container/throughput-credentials.json' -BaseUrl 'http://127.0.0.1:18080' -SimulatorUrl 'http://127.0.0.1:19091' -DatabaseUrl 'jdbc:mysql://127.0.0.1:14007/merchantpay?connectionTimeZone=UTC' -NotificationUrl 'http://simulator:9091/merchant-webhook'
    } catch {$taskFailure=$_.Exception.Message}
    $taskCreated=@(Get-ChildItem '.work/acceptance' -Directory -Filter 'accept-*' | Where-Object {$_.Name -notin $taskBefore})
    if($taskCreated.Count -ne 1){throw 'Cannot uniquely identify this workload; inspect .work/acceptance'}
    $taskReports=@(Get-ChildItem -LiteralPath $taskCreated[0].FullName -Filter '*-r1.json')
    if($taskReports.Count -ne 1){throw "No complete workload report. $taskFailure"}
    $taskReport=Get-Content -LiteralPath $taskReports[0].FullName -Raw | ConvertFrom-Json
    [pscustomobject]@{
        report=$taskReports[0].FullName
        paymentCompletedTps=$taskReport.payment.completedPerArrivalSecondLower
        refundCompletedTps=$taskReport.refund.completedPerArrivalSecondLower
        querySuccessfulQps=$taskReport.query.successfulPerArrivalSecond
        paymentErrors=$taskReport.payment.systemErrors
        refundErrors=$taskReport.refund.systemErrors
        queryErrors=$taskReport.query.systemErrors
        complete=$taskReport.complete
        moneyInvariants=$taskReport.manifest.moneyInvariantsPassed
        deliveryConverged=$taskReport.manifest.deliveryConverged
        channelTruth=$taskReport.truth.matched
        workloadGateFailure=$taskFailure
        environment='Local Docker Desktop; inspect active durability profile'
    }|ConvertTo-Json
    if($taskFailure){throw $taskFailure}
} finally {Pop-Location}
