[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Trim','Restore')][string]$Mode,
    [string]$EvidencePath
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskMysql='merchantpay-container-v1-mysql-1'
if(-not $EvidencePath){$EvidencePath=Join-Path $taskRoot ('.work/task-index-profile-'+[DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfff')+'.json')}
if(Test-Path -LiteralPath $EvidencePath){throw 'Evidence path already exists'}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent ([IO.Path]::GetFullPath($EvidencePath))) | Out-Null
$taskOutcome=[ordered]@{at=[DateTimeOffset]::UtcNow.ToString('o');mode=$Mode;container=$taskMysql;ddlAttempted=$false;changed=$false;passed=$false;failure=$null}
$taskLock=$null
function Invoke-ProfileDocker([string[]]$DockerArgs){
    $taskOutput=& docker --context desktop-linux @DockerArgs
    if($LASTEXITCODE -ne 0){throw 'Docker/MySQL command failed; inspect evidence and schema before retrying'}
    $taskOutput
}
function Assert-ProfileWritersStopped {
    $taskIds=@(Invoke-ProfileDocker @('ps','-a','--filter','label=com.docker.compose.project=merchantpay-container-v1','--format','{{.ID}}'))
    if(-not $taskIds.Count){throw 'Local project containers not found'}
    $taskContainers=Invoke-ProfileDocker (@('inspect')+$taskIds) | ConvertFrom-Json
    $taskDatabase=@($taskContainers | Where-Object {$_.Name -eq ('/'+$taskMysql)})
    if($taskDatabase.Count -ne 1 -or -not $taskDatabase[0].State.Running -or $taskDatabase[0].State.Health.Status -ne 'healthy'){throw 'Start only the local project MySQL and wait for healthy status first'}
    if(@($taskContainers | Where-Object {$_.Name -ne ('/'+$taskMysql) -and $_.State.Running}).Count){throw 'Stop all other local project containers and external clients before changing indexes'}
}
function Invoke-ProfileSql([string]$Sql){
    $taskOutput=$Sql | & docker --context desktop-linux exec -i $taskMysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD exec mysql -u root -N -B merchantpay'
    if($LASTEXITCODE -ne 0){throw 'MySQL command failed; no successful profile transition is certified'}
    $taskOutput
}
function Get-ProfileIndexes {
    $taskMap=@{}
    foreach($taskLine in (Invoke-ProfileSql @'
SELECT INDEX_NAME,NON_UNIQUE,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ','),
 SUM(SUB_PART IS NOT NULL OR COLLATION<>'A' OR INDEX_TYPE<>'BTREE' OR IS_VISIBLE<>'YES' OR EXPRESSION IS NOT NULL)
FROM information_schema.statistics WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='outbox_tasks'
GROUP BY INDEX_NAME,NON_UNIQUE ORDER BY INDEX_NAME;
'@)){
        $taskFields=$taskLine -split "`t"
        if($taskFields.Count -ne 4 -or $taskFields[3] -ne '0' -or $taskMap.ContainsKey($taskFields[0])){throw 'Unexpected task index metadata'}
        $taskMap[$taskFields[0]]=$taskFields[1]+'|'+$taskFields[2]
    }
    $taskMap
}
function Get-ProfileDrain {
    $taskMap=@{}
    foreach($taskLine in (Invoke-ProfileSql @'
SELECT 'occupied_slots',COUNT(*) FROM payment_admission_slots WHERE payment_id IS NOT NULL;
SELECT 'counter_inflight',COALESCE(SUM(inflight),0) FROM payment_admission;
SELECT 'pending_tasks',COUNT(*) FROM outbox_tasks WHERE status<>'DONE';
SELECT 'nonterminal_payments',COUNT(*) FROM payments WHERE status NOT IN ('SUCCEEDED','FAILED','CLOSED');
SELECT 'nonterminal_refunds',COUNT(*) FROM refunds WHERE status NOT IN ('SUCCEEDED','FAILED','CLOSED');
'@)){
        $taskFields=$taskLine -split "`t"
        if($taskFields.Count -ne 2 -or $taskMap.ContainsKey($taskFields[0])){throw 'Unexpected drain metadata'}
        $taskMap[$taskFields[0]]=[long]$taskFields[1]
    }
    foreach($taskKey in @('occupied_slots','counter_inflight','pending_tasks','nonterminal_payments','nonterminal_refunds')){
        if(-not $taskMap.ContainsKey($taskKey) -or $taskMap[$taskKey] -ne 0){throw ('Not drained: '+$taskKey)}
    }
    $taskMap
}
function Format-ProfileIndexes($TaskMap){(@($TaskMap.Keys | Sort-Object | ForEach-Object {$_+'='+$TaskMap[$_]})) -join "`n"}
try {
    New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot '.work') | Out-Null
    try{$taskLock=[IO.File]::Open((Join-Path $taskRoot '.work/performance-baseline.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}
    catch [IO.IOException]{throw 'A workload, startup or maintenance operation holds the workspace lock'}
    $taskContext=Invoke-ProfileDocker @('context','inspect','desktop-linux') | ConvertFrom-Json
    $taskOutcome.dockerEndpoint=$taskContext[0].Endpoints.docker.Host
    if($taskOutcome.dockerEndpoint -ne 'npipe:////./pipe/dockerDesktopLinuxEngine'){throw 'Only the local Docker Desktop Linux endpoint is supported'}
    Assert-ProfileWritersStopped
    $taskOutcome.before=Get-ProfileIndexes
    $taskRequired=@{PRIMARY='0|id';uq_outbox_event='0|event_id';ix_task_class_ready='1|queue_class,status,due_at,id';ix_task_class_lease='1|queue_class,status,lease_until,id';ix_task_merchant_time='1|merchant_id,created_at,id';ix_task_aggregate='1|aggregate_id,created_at';ix_task_archive_due='1|status,updated_at,id'}
    foreach($taskKey in $taskRequired.Keys){if($taskOutcome.before[$taskKey] -ne $taskRequired[$taskKey]){throw ('Required task index differs: '+$taskKey)}}
    $taskGlobals=@{ix_task_ready='1|status,due_at,id';ix_task_expired_lease='1|status,lease_until,id'}
    $taskPresent=0
    foreach($taskKey in $taskGlobals.Keys){
        if($taskOutcome.before.ContainsKey($taskKey)){
            if($taskOutcome.before[$taskKey] -ne $taskGlobals[$taskKey]){throw ('Global task index differs: '+$taskKey)}
            $taskPresent++
        }
    }
    if($taskPresent -eq 1){throw 'Partial task index profile; inspect before proceeding'}
    $taskOutcome.drainBefore=Get-ProfileDrain
    $taskExpected=$taskOutcome.before.Clone()
    if($Mode -eq 'Trim'){foreach($taskKey in $taskGlobals.Keys){$taskExpected.Remove($taskKey)}}
    else{foreach($taskKey in $taskGlobals.Keys){$taskExpected[$taskKey]=$taskGlobals[$taskKey]}}
    if((Format-ProfileIndexes $taskExpected) -ne (Format-ProfileIndexes $taskOutcome.before)){
        $taskSqlPath=Join-Path $taskRoot ('infra/mysql/local-max-task-indexes-'+$Mode.ToLowerInvariant()+'.sql')
        $taskOutcome.sqlSha256=(Get-FileHash -LiteralPath $taskSqlPath -Algorithm SHA256).Hash.ToLowerInvariant()
        Assert-ProfileWritersStopped
        $taskOutcome.ddlAttempted=$true
        Invoke-ProfileSql ('SET SESSION lock_wait_timeout=5;'+"`n"+(Get-Content -LiteralPath $taskSqlPath -Raw)) | Out-Null
        $taskOutcome.changed=$true
    }
    $taskOutcome.after=Get-ProfileIndexes
    if((Format-ProfileIndexes $taskExpected) -ne (Format-ProfileIndexes $taskOutcome.after)){throw 'Post-DDL index set differs; inspect before starting writers'}
    $taskOutcome.drainAfter=Get-ProfileDrain
    Assert-ProfileWritersStopped
    $taskOutcome.passed=$true
}catch{$taskOutcome.failure=$_.Exception.Message;throw}
finally{
    try{$taskOutcome | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $EvidencePath -Encoding utf8}
    finally{if($taskLock){$taskLock.Dispose()}}
}
Write-Output ('Task index profile verified: '+$Mode+'; changed='+$taskOutcome.changed+'; evidence='+$EvidencePath)
