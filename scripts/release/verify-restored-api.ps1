[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]{1,24}$')][string]$Id)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $taskRoot 'scripts/ProcessEnvironment.ps1')
$taskFolder=Join-Path $taskRoot ('.data/restores/'+$Id)
$taskProject='merchantpay-restore-'+$Id
$taskContainers=@();$taskProcesses=@();$taskSaved=@{}
function Stop-RestoredProcesses {
    foreach($taskRecord in $taskProcesses){$taskProcess=Get-Process -Id $taskRecord.pid -ErrorAction SilentlyContinue;if($taskProcess -and $taskProcess.StartTime.ToUniversalTime().Ticks -eq $taskRecord.ticks -and $taskProcess.Path -eq $taskRecord.exe){Stop-Process -Id $taskRecord.pid;Wait-Process -Id $taskRecord.pid -Timeout 15 -ErrorAction SilentlyContinue}}
}
Push-Location $taskRoot
try {
    $taskManifest=Get-Content (Join-Path $taskFolder 'manifest.json') -Raw|ConvertFrom-Json
    $taskVerified=Get-Content ('.work/release/restore-'+$Id+'.json') -Raw|ConvertFrom-Json
    if($taskManifest.source -ne 'native' -or $taskManifest.includesS3 -or $taskVerified.complete -ne $true){throw 'Application recovery requires an already verified native local-storage restore set'}
    if(Test-Path ('.work/release/restore-'+$Id+'-api.json')){throw 'This application recovery already has final evidence; keep it immutable'}
    foreach($taskLine in [IO.File]::ReadAllLines((Join-Path $taskFolder 'runtime.env'))) {
        if($taskLine -match '^([A-Z_]+)=(.*)$'){$taskSaved[$Matches[1]]=[Environment]::GetEnvironmentVariable($Matches[1],'Process');[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
    }
    $taskOverrides=@{DATABASE_URL='jdbc:mysql://127.0.0.1:13309/merchantpay?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true';DATA_DIRECTORY=(Join-Path $taskFolder 'platform');BIND_ADDRESS='127.0.0.1';PLATFORM_PORT='18082';SIMULATOR_PORT='19092';CHANNEL_BASE_URL='http://127.0.0.1:19092';CALLBACK_URL='http://127.0.0.1:18082/api/channel/callback';OIDC_BASE_URL='http://127.0.0.1:18181';LOCAL_WEBHOOK='http://127.0.0.1:19092/merchant-webhook';OBJECT_STORAGE_TYPE='local';RESTORE_HAS_S3='false';SIMULATOR_PROFILE='demo';SIMULATOR_DATABASE_URL=('jdbc:h2:file:'+(Join-Path $taskFolder 'simulator/channel').Replace('\','/')+';MODE=MySQL;IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE');WORKERS_ENABLED='true'}
    foreach($taskName in $taskOverrides.Keys){if(-not $taskSaved.ContainsKey($taskName)){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')};[Environment]::SetEnvironmentVariable($taskName,$taskOverrides[$taskName],'Process')}
    foreach($taskService in @('db','keycloak')) {
        $taskContainer=$taskProject+'-'+$taskService
        $taskState=(& docker inspect $taskContainer|ConvertFrom-Json)[0]
        if($LASTEXITCODE -ne 0 -or $taskState.Config.Labels.'dev.merchantpay.restore' -ne $Id -or $taskState.State.Running){throw 'Recovery requires the stopped, explicitly owned restore containers'}
        & docker start $taskContainer|Out-Null;if($LASTEXITCODE -ne 0){throw 'Restore service startup failed'};$taskContainers+=@($taskContainer)
    }
    $taskIdentityReady=$false
    for($taskAttempt=0;$taskAttempt -lt 90;$taskAttempt++){try{Invoke-RestMethod 'http://127.0.0.1:18181/realms/merchantpay/protocol/openid-connect/certs' -TimeoutSec 2|Out-Null;$taskIdentityReady=$true;break}catch{};Start-Sleep -Seconds 1}
    if(-not $taskIdentityReady){throw 'Restored identity service did not become ready'}
    foreach($taskPort in @(18082,19092)){if(Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue){throw 'Restore application port is already in use'}}
    $taskJava=(Get-ChildItem '.tools/java25' -Directory|Select-Object -First 1).FullName
    foreach($taskService in @('simulator','platform')) {
        $taskHeap=if($taskService -eq 'platform'){'-Xmx768m'}else{'-Xmx320m'}
        $taskJar=Join-Path $taskRoot ($taskService+'/target/'+$taskService+'-1.0.0-SNAPSHOT.jar')
        $taskProcess=Start-Process -FilePath (Join-Path $taskJava 'bin/java.exe') -ArgumentList @($taskHeap,'-XX:ActiveProcessorCount=2','-jar',('"'+$taskJar+'"'),'--spring.profiles.active=local') -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput ('.work/restore-'+$Id+'-'+$taskService+'.log') -RedirectStandardError ('.work/restore-'+$Id+'-'+$taskService+'.err.log')
        $taskProcesses+=@{pid=$taskProcess.Id;ticks=$taskProcess.StartTime.ToUniversalTime().Ticks;exe=$taskProcess.Path}
        $taskPort=if($taskService -eq 'platform'){18082}else{19092};$taskReady=$false
        for($taskAttempt=0;$taskAttempt -lt 120;$taskAttempt++){try{if((Invoke-RestMethod ('http://127.0.0.1:'+$taskPort+'/actuator/health') -TimeoutSec 2).status -eq 'UP'){$taskReady=$true;break}}catch{};Start-Sleep -Seconds 1}
        if(-not $taskReady){throw ('Restored '+$taskService+' did not become healthy')}
    }
    $taskNode=(Get-ChildItem '.tools/node24' -Directory|Select-Object -First 1).FullName
    & (Join-Path $taskNode 'node.exe') scripts/api/restored-client.mjs $Id
    if($LASTEXITCODE -ne 0){throw 'Restored signed merchant API workflow failed'}
    $taskConverged=$false
    $taskBusinessHex=[Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes('restore-api-'+$Id))
    $taskSql="SELECT (SELECT COUNT(*) FROM outbox_tasks WHERE status<>'DONE'),(SELECT COUNT(DISTINCT d.task_id) FROM notification_deliveries d JOIN outbox_tasks o ON o.id=d.task_id LEFT JOIN refunds r ON o.kind='REFUND_SUCCEEDED' AND r.id=o.aggregate_id JOIN payments p ON p.id=COALESCE(r.payment_id,o.aggregate_id) WHERE p.merchant_order_no=0x$taskBusinessHex AND o.kind IN ('PAYMENT_SUCCEEDED','REFUND_SUCCEEDED') AND d.status='DELIVERED' AND d.http_status BETWEEN 200 AND 299),(SELECT COUNT(*) FROM ledger_journals j JOIN payments p ON p.id=j.payment_id WHERE p.merchant_order_no=0x$taskBusinessHex),(SELECT COUNT(*) FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id JOIN payments p ON p.id=j.payment_id WHERE p.merchant_order_no=0x$taskBusinessHex),(SELECT refunded_minor FROM payments WHERE merchant_order_no=0x$taskBusinessHex)"
    for($taskAttempt=0;$taskAttempt -lt 90;$taskAttempt++) {
        $taskCounts=& docker exec -e ('RESTORE_SQL='+$taskSql) ($taskProject+'-db') sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u merchantpay -N merchantpay -e "$RESTORE_SQL"'
        if($LASTEXITCODE -ne 0){throw 'Restored ledger/delivery convergence query failed'}
        if($taskCounts.Trim() -eq "0`t2`t2`t6`t3000"){$taskConverged=$true;break}
        Start-Sleep -Seconds 1
    }
    if(-not $taskConverged){throw 'Restored application tasks, two HTTP deliveries, two journals or six entries did not converge'}
    Stop-RestoredProcesses;$taskProcesses=@()
    $env:DATABASE_URL='jdbc:mysql://127.0.0.1:13309/merchantpay?connectionTimeZone=UTC'
    $taskH2=(Get-ChildItem '.tools/m2/com/h2database/h2' -Recurse -Filter '*.jar'|Select-Object -First 1).FullName
    $taskClasspath='.work/restore-classes;platform/target/classes;simulator/target/classes;'+(Get-Content 'platform/target/storage-classpath.txt' -Raw).Trim()+';'+$taskH2
    & (Join-Path $taskJava 'bin/java.exe') -Xmx1536m -cp $taskClasspath RestoredSetCheck $taskFolder ('.work/release/restore-'+$Id+'-api-facts.json')
    if($LASTEXITCODE -ne 0){throw 'Whole restored financial/channel/object facts failed after application traffic'}
    @{at=[DateTimeOffset]::UtcNow.ToString('o');backupId=$Id;complete=$true;signedPaymentAndRefund=$true;crossMerchant404=$true;journalsForPayment=2;ledgerEntriesForPayment=6;actualHttp2xxDeliveries=2;wholeRestoredFactsPassed=$true;sourceFinancialWrites=0;referenceR1Certified=$false;limits=@('Local Windows processes and isolated Docker data/ports, not an independent host','OIDC identity keys verified separately; no restored browser sign-in or formal RPO/RTO claim')}|ConvertTo-Json -Depth 5|Set-Content ('.work/release/restore-'+$Id+'-api.json')
    Write-Output 'Restored application payment/refund, tenant isolation, HTTP delivery and whole-set financial/channel/object checks passed.'
} finally {
    try {Stop-RestoredProcesses} finally {
        try {if($taskContainers.Count -gt 0){& docker stop -t 15 @taskContainers|Out-Null;if($LASTEXITCODE -ne 0){throw 'Owned restore containers did not stop'}}}finally{
            Restore-ProcessEnvironment $taskSaved
            Pop-Location
        }
    }
}
