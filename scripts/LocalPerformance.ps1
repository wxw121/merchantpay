# Shared by local startup, load generation and baseline checks. No environment or runtime mutation here.
function Assert-LocalProfileChange([string]$Requested,[string]$Current,[bool]$Running) {
    if($Running -and $Requested -ne $Current) { throw 'Stop project processes before switching normal/performance profiles; database resources must not change underneath live writers.' }
}
function Assert-PerformanceCpu([int]$LogicalProcessors,[long]$AllowedMask) {
    if($LogicalProcessors -lt 8 -or ($AllowedMask -band 255) -ne 255) { throw 'D0 performance mode requires eight allowed logical CPUs (0..7) in this Windows processor group.' }
}
function Get-LocalPerformanceDefinition {
    [pscustomobject]@{id='d0-v1';mysqlCpu=4;mysqlMemoryBytes=4294967296;bufferPoolBytes=1073741824;platformAffinity=15;platformCpu=4;platformHeapMiB=1536;simulatorAffinity=48;simulatorCpu=2;simulatorHeapMiB=768;loadAffinity=192;loadCpu=2;loadHeapMiB=512}
}
function Get-LocalBaselineVerdict($Warmup,[object[]]$Rounds) {
    $taskProblems=[Collections.Generic.List[string]]::new()
    if($Warmup.arrivalWindowSeconds -lt 600) { $taskProblems.Add('Warmup is shorter than600seconds') }
    if($Rounds.Count -lt 3) { $taskProblems.Add('Fewer than3rounds') }
    $taskRate=if($Rounds.Count){$Rounds[0].requestedRate}else{0}
    foreach($taskReport in (@($Warmup)+@($Rounds))) {
        if($taskReport.onlineCompletionMeasured -eq $false){$taskProblems.Add('Offline observer control cannot qualify as an online baseline')}
        if($taskReport.loadExit -ne 0){$taskProblems.Add('Generator exited unsuccessfully')}
        if($taskReport.requests -ne $taskReport.requestedRate*$taskReport.arrivalWindowSeconds){$taskProblems.Add('Requested workload is inconsistent')}
        if($taskReport.scenario -ne 'pay' -or $taskReport.correctnessPassed -ne $true -or $taskReport.moneyInvariantsPassed -ne $true -or $taskReport.transportErrors -ne 0 -or $taskReport.notSentDueCapacity -ne 0 -or $taskReport.observerErrors -ne 0 -or $taskReport.unfinishedAcceptedPayments -ne 0 -or $taskReport.postedPayments -ne $taskReport.acceptedOrRead) { $taskProblems.Add('Incomplete, rejected or incorrect run') }
        if(-not $taskReport.profileSignature -or $taskReport.profileSignature -ne $Warmup.profileSignature) { $taskProblems.Add('Resource/runtime fingerprint changed') }
        if($taskReport.generator.availableProcessors -ne 2) { $taskProblems.Add('Load JVM processor count is not2') }
    }
    foreach($taskReport in $Rounds) {
        if($taskReport.arrivalWindowSeconds -lt 60 -or $taskReport.requestedRate -ne $taskRate) { $taskProblems.Add('Measured duration/rate differs from protocol') }
        if($null -eq $taskReport.successfulCompletionP99UpperMicros -or $taskReport.successfulCompletionP99UpperMicros -le 0) { $taskProblems.Add('Completion P99 unavailable') }
        if($null -eq $taskReport.completedPerArrivalSecondLower -or $taskReport.completedPerArrivalSecondLower -lt .95*$taskRate) { $taskProblems.Add('Completed throughput lower bound below95%of scheduled rate') }
    }
    $taskTps=@($Rounds | ForEach-Object completedPerArrivalSecondLower)
    $taskLatency=@($Rounds | ForEach-Object successfulCompletionP99UpperMicros | Where-Object { $null -ne $_ -and $_ -gt 0 })
    $taskTpsMean=($taskTps | Measure-Object -Average).Average
    $taskSpread=if($taskTpsMean -gt 0){(($taskTps | Measure-Object -Maximum).Maximum-($taskTps | Measure-Object -Minimum).Minimum)/$taskTpsMean}else{$null}
    $taskRatio=if($taskLatency.Count -eq $Rounds.Count -and $taskLatency.Count){($taskLatency | Measure-Object -Maximum).Maximum/($taskLatency | Measure-Object -Minimum).Minimum}else{$null}
    if($null -eq $taskSpread -or $taskSpread -gt .1) { $taskProblems.Add('Throughput spread exceeds10%') }
    if($null -eq $taskRatio -or $taskRatio -gt 1.5) { $taskProblems.Add('P99 upper-bound spread exceeds1.5x') }
    [pscustomobject]@{qualified=($taskProblems.Count -eq 0);problems=@($taskProblems.ToArray());rounds=$Rounds.Count;throughputSpreadFraction=$taskSpread;p99UpperRatio=$taskRatio;referenceR1Certified=$false}
}
function Get-ObserverCalibrationVerdict($Warmup,[object[]]$Rounds) {
    $taskProblems=[Collections.Generic.List[string]]::new()
    if($Warmup.arrivalWindowSeconds -lt 600 -or $Warmup.observerMode -ne 'full' -or $Rounds.Count -ne 4){$taskProblems.Add('Calibration needs600second full warmup and4rounds')}
    $taskOrder=@('full','after','after','full')
    for($taskIndex=0;$taskIndex -lt $Rounds.Count;$taskIndex++){
        if($taskIndex -ge 4 -or $Rounds[$taskIndex].observerMode -ne $taskOrder[$taskIndex] -or $Rounds[$taskIndex].arrivalWindowSeconds -lt 60 -or $Rounds[$taskIndex].requestedRate -ne $Rounds[0].requestedRate){$taskProblems.Add('Calibration order/rate/duration differs from protocol')}
    }
    foreach($taskRun in (@($Warmup)+@($Rounds))){
        if(-not $taskRun.profileSignature -or $taskRun.profileSignature -ne $Warmup.profileSignature -or $taskRun.generator.availableProcessors -ne 2 -or $taskRun.requests -ne $taskRun.requestedRate*$taskRun.arrivalWindowSeconds){$taskProblems.Add('Workload/resource drift')}
        if($taskRun.moneyInvariantsPassed -ne $true -or $taskRun.unfinishedAcceptedPayments -ne 0 -or $taskRun.transportErrors -ne 0 -or $taskRun.notSentDueCapacity -ne 0 -or $taskRun.observerErrors -ne 0 -or $taskRun.unacknowledgedCommittedPayments -ne 0 -or $taskRun.uniquePayments -ne $taskRun.acceptedOrRead -or $taskRun.postedPayments -ne $taskRun.acceptedOrRead){$taskProblems.Add('Incomplete, unobserved or ambiguous requests')}
        if(-not $taskRun.httpStatuses -or @($taskRun.httpStatuses.PSObject.Properties | Where-Object { $_.Name -notin @('202','503') }).Count){$taskProblems.Add('Unexpected HTTP status outside202/503')}
    }
    [pscustomobject]@{dataValid=($taskProblems.Count -eq 0);problems=@($taskProblems.ToArray());qualified=$false;referenceR1Certified=$false}
}
function Assert-ApiAvailabilityMetrics([string[]]$Lines) {
    $taskExpected=@(foreach($taskReason in @('API_INFLIGHT','QUOTA_CAPACITY','QUOTA_TIMEOUT','QUOTA_INTERRUPTED','QUOTA_UNAVAILABLE','QUOTA_DATABASE','PAYMENT_CAPACITY','DATABASE_UNAVAILABLE','DATABASE_TRANSIENT','UNCLASSIFIED')){'merchantpay_api_unavailable_total{reason="'+$taskReason+'"}'}; foreach($taskStage in @('AUTH','NONCE','QUOTA','HANDLER')){foreach($taskKind in @('count','sum')){'merchantpay_api_stage_seconds_'+$taskKind+'{stage="'+$taskStage+'"}'}})
    foreach($taskSeries in $taskExpected){
        $taskMatches=@($Lines | Where-Object { $_ -match ('^'+[regex]::Escape($taskSeries)+'\s+[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?\s*$') })
        if($taskMatches.Count -ne 1){throw ('Missing/invalid/duplicate API instrumentation series: '+$taskSeries)}
    }
}
