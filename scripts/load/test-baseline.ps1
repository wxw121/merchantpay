$ErrorActionPreference='Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'LocalPerformance.ps1')
function Check([bool]$Value,[string]$Message) { if(-not $Value) { throw $Message } }
function Expect-Rejection([scriptblock]$Action) { $taskRejected=$false; try { & $Action } catch { $taskRejected=$true }; Check $taskRejected 'Expected unsafe profile transition/host to be rejected' }
Expect-Rejection { Assert-LocalProfileChange performance normal $true }
Assert-LocalProfileChange performance performance $true
Assert-LocalProfileChange normal performance $false
Expect-Rejection { Assert-PerformanceCpu 4 15 }
Expect-Rejection { Assert-PerformanceCpu 32 15 }
Assert-PerformanceCpu 32 4294967295
function Report([int]$Seconds=60) {
    [pscustomobject]@{scenario='pay';requestedRate=50;requests=50*$Seconds;arrivalWindowSeconds=$Seconds;correctnessPassed=$true;moneyInvariantsPassed=$true;transportErrors=0;notSentDueCapacity=0;observerErrors=0;unfinishedAcceptedPayments=0;acceptedOrRead=50*$Seconds;postedPayments=50*$Seconds;completedPerArrivalSecondLower=49.8;completedPerArrivalSecondUpper=50;successfulCompletionP99UpperMicros=500000;generator=[pscustomobject]@{availableProcessors=2};profileSignature='same';loadExit=0}
}
$taskWarmup=Report 600
$taskRounds=@((Report),(Report),(Report))
$taskResult=Get-LocalBaselineVerdict $taskWarmup $taskRounds
Check $taskResult.qualified 'Valid warmed repeat set must qualify as a D0 comparison baseline'
$taskShort=Report 599
Check (-not (Get-LocalBaselineVerdict $taskShort $taskRounds).qualified) 'Short warmup must not qualify'
$taskBad=Report; $taskBad.correctnessPassed=$false
Check (-not (Get-LocalBaselineVerdict $taskWarmup @((Report),$taskBad,(Report))).qualified) 'Failed rounds must not be dropped or hidden'
$taskChanged=Report; $taskChanged.profileSignature='other'
Check (-not (Get-LocalBaselineVerdict $taskWarmup @((Report),$taskChanged,(Report))).qualified) 'Configuration drift must invalidate comparisons'
$taskSlow=Report; $taskSlow.completedPerArrivalSecondLower=30
Check (-not (Get-LocalBaselineVerdict $taskWarmup @((Report),$taskSlow,(Report))).qualified) 'Variable throughput must not be called stable'
$taskLatency=Report; $taskLatency.successfulCompletionP99UpperMicros=$null
Check (-not (Get-LocalBaselineVerdict $taskWarmup @((Report),$taskLatency,(Report))).qualified) 'Missing latency is not healthy zero'
Check (-not (Get-LocalBaselineVerdict $taskWarmup @((Report),(Report))).qualified) 'Need three complete rounds'
$taskExit=Report; $taskExit.loadExit=7
Check (-not (Get-LocalBaselineVerdict $taskWarmup @((Report),$taskExit,(Report))).qualified) 'Nonzero generator exit must not qualify despite a complete-looking report'
$taskOffline=Report 600; $taskOffline | Add-Member onlineCompletionMeasured $false
Check (-not (Get-LocalBaselineVerdict $taskOffline $taskRounds).qualified) 'An offline calibration control must never qualify as an online baseline'
function CalibrationReport([string]$Mode,[int]$Duration=60) {
    $taskReport=Report $Duration
    $taskReport | Add-Member observerMode $Mode
    $taskReport | Add-Member uniquePayments $taskReport.acceptedOrRead
    $taskReport | Add-Member unacknowledgedCommittedPayments 0
    $taskReport | Add-Member httpStatuses ([pscustomobject]@{'202'=$taskReport.acceptedOrRead})
    $taskReport
}
$taskControlWarmup=CalibrationReport full 600
$taskControls=@((CalibrationReport full),(CalibrationReport after),(CalibrationReport after),(CalibrationReport full))
Check (Get-ObserverCalibrationVerdict $taskControlWarmup $taskControls).dataValid 'ABBA with complete data must be a valid control experiment'
Check (-not (Get-ObserverCalibrationVerdict $taskControlWarmup $taskControls).qualified) 'Valid calibration is not baseline qualification'
$taskControls[2].profileSignature='restarted'
Check (-not (Get-ObserverCalibrationVerdict $taskControlWarmup $taskControls).dataValid) 'Cross-round runtime drift must invalidate calibration'
$taskControls[2].profileSignature='same'; $taskControls[2].transportErrors=1
Check (-not (Get-ObserverCalibrationVerdict $taskControlWarmup $taskControls).dataValid) 'Incomplete generator traffic must invalidate calibration'
$taskControls[2].transportErrors=0; $taskControls[2].moneyInvariantsPassed=$false
Check (-not (Get-ObserverCalibrationVerdict $taskControlWarmup $taskControls).dataValid) 'Control mode cannot bypass financial correctness'
Expect-Rejection { Assert-ApiAvailabilityMetrics @() }
$taskMetrics=@(foreach($taskReason in @('API_INFLIGHT','QUOTA_CAPACITY','QUOTA_TIMEOUT','QUOTA_INTERRUPTED','QUOTA_UNAVAILABLE','QUOTA_DATABASE','PAYMENT_CAPACITY','DATABASE_UNAVAILABLE','DATABASE_TRANSIENT','UNCLASSIFIED')){'merchantpay_api_unavailable_total{reason="'+$taskReason+'"} 0.0'}; foreach($taskStage in @('AUTH','NONCE','QUOTA','HANDLER')){foreach($taskKind in @('count','sum')){'merchantpay_api_stage_seconds_'+$taskKind+'{stage="'+$taskStage+'"} 0.0'}})
Assert-ApiAvailabilityMetrics $taskMetrics
Expect-Rejection { Assert-ApiAvailabilityMetrics $taskMetrics[1..($taskMetrics.Count-1)] }
Expect-Rejection { Assert-ApiAvailabilityMetrics (@($taskMetrics)+@($taskMetrics[0])) }
Write-Output 'Local baseline qualification tests passed.'
