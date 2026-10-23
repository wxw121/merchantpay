$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'V1Acceptance.ps1')
$taskZero=@{scheduled=0;systemErrors=0;droppedIterations=0;rejected=0;unfinished=0;unacknowledgedCommitted=0}
$taskGood=@{complete=$true;onlineCompletionMeasured=$true;observerErrors=0;truth=@{matched=$true};manifest=@{moneyInvariantsPassed=$true;deliveryConverged=$true;d1CountersSatisfied=$true};durationSeconds=900;payment=@{scheduled=276300;systemErrors=0;droppedIterations=0;rejected=0;unfinished=0;unacknowledgedCommitted=0;completedPerArrivalSecondLower=300;successfulCompletionP99UpperMicros=2000000;httpP99Micros=200000};refund=@{scheduled=27900;systemErrors=0;droppedIterations=0;rejected=0;unfinished=0;unacknowledgedCommitted=0;completedPerArrivalSecondLower=30;httpP99Micros=200000};query=$taskZero}
if(-not (Get-V1AcceptanceVerdict $taskGood C1 $false).localWorkloadPassed){throw 'Complete committed workload should pass its local target'}
$taskGood.payment.completedPerArrivalSecondLower=100
if((Get-V1AcceptanceVerdict $taskGood C1 $false).localWorkloadPassed){throw 'Accepted requests cannot replace completed TPS'}
$taskGood.payment.completedPerArrivalSecondLower=300
$taskGood.manifest.d1CountersSatisfied=$false
if((Get-V1AcceptanceVerdict $taskGood C1 $false).localWorkloadPassed){throw 'Scaled dataset must fail formal workload'}
if((Get-V1AcceptanceVerdict $taskGood C1 $true).referenceR1Certified){throw 'Shared-host scaled data must not certify R1'}
if((Get-V1AcceptanceVerdict @{} C1 $false).localWorkloadPassed){throw 'Missing counters must fail closed'}
$taskEmpty=@{complete=$true;onlineCompletionMeasured=$true;observerErrors=0;truth=@{matched=$true};manifest=@{moneyInvariantsPassed=$true};payment=$taskZero;refund=$taskZero;query=$taskZero}
if((Get-V1AcceptanceVerdict $taskEmpty Smoke $true).localWorkloadPassed){throw 'Empty workloads must not pass through vacuous truth'}
$taskGood.manifest.moneyInvariantsPassed=$false
if((Get-V1AcceptanceVerdict $taskGood Smoke $true).localWorkloadPassed){throw 'Scaled financial mismatch must fail'}
$taskC2=@{complete=$true;onlineCompletionMeasured=$true;observerErrors=0;truth=@{matched=$true};manifest=@{moneyInvariantsPassed=$true;deliveryConverged=$true;d1CountersSatisfied=$true};durationSeconds=900;payment=$taskZero;refund=$taskZero;query=@{scheduled=1845000;systemErrors=1;droppedIterations=0;rejected=0;successfulPerArrivalSecond=2000;httpP99Micros=10000}}
if(-not (Get-V1AcceptanceVerdict $taskC2 C2 $false).localWorkloadPassed){throw 'Arrival headroom does not require zero query errors'}
$taskC2.query.successfulPerArrivalSecond=1999.99
if((Get-V1AcceptanceVerdict $taskC2 C2 $false).localWorkloadPassed){throw 'Tail RTT must not lower the successful query target'}
$taskC2.tasks=@{consoleCalls=20;consoleErrors=1;reconciliation=@(@{status='COMPLETE';rows=2});exports=@(@{status='COMPLETE';rows=2})}
if((Get-V1AcceptanceVerdict $taskC2 C3 $true).localWorkloadPassed){throw 'Scaled mixed tests must reject real console errors'}
$taskC2.tasks.consoleErrors=0
if(-not (Get-V1AcceptanceVerdict $taskC2 C3 $true).localWorkloadPassed){throw 'Nonempty complete scaled batches should pass only the local mixed gate'}
$taskC2.tasks.exports[0].rows=0
if((Get-V1AcceptanceVerdict $taskC2 Soak $true).localWorkloadPassed){throw 'Scaled soak must not accept empty batch objects'}
$taskGood.manifest.moneyInvariantsPassed=$true
$taskGood.manifest.deliveryConverged=$false
if((Get-V1AcceptanceVerdict $taskGood Smoke $true).localWorkloadPassed){throw 'Posted money with missing HTTP delivery must not pass'}
Write-Output 'V1 workload qualification guards passed.'
