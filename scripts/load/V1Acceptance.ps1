function Get-V1AcceptanceVerdict($Report,[string]$Protocol,[bool]$Scaled) {
    $taskProblems=[Collections.Generic.List[string]]::new()
    if(($Report.payment.scheduled+$Report.refund.scheduled+$Report.query.scheduled) -le 0){$taskProblems.Add('Empty workload has no measured requests')}
    if($Report.complete -ne $true -or $Report.onlineCompletionMeasured -ne $true -or $Report.observerErrors -ne 0 -or $Report.truth.matched -ne $true -or $Report.manifest.moneyInvariantsPassed -ne $true){$taskProblems.Add('Incomplete commit observation or financial/channel truth mismatch')}
    if($Report.manifest.deliveryConverged -ne $true){$taskProblems.Add('Task drain and real merchant HTTP delivery not verified')}
    foreach($taskPart in @($Report.payment,$Report.refund,$Report.query)) {
        if($null -eq $taskPart.scheduled -or $null -eq $taskPart.systemErrors -or $null -eq $taskPart.droppedIterations){$taskProblems.Add('Missing workload counters');continue}
        if($taskPart.droppedIterations -gt 0 -or $taskPart.rejected -gt 0 -or ($taskPart.systemErrors/[math]::Max(1,$taskPart.scheduled)) -gt .001){$taskProblems.Add('Rejected, dropped or excessive system-error samples')}
    }
    foreach($taskPart in @($Report.payment,$Report.refund)) {
        if($taskPart.unfinished -ne 0 -or $taskPart.unacknowledgedCommitted -ne 0){$taskProblems.Add('Unfinished or ambiguous financial requests')}
    }
    if($Protocol -in @('C3','Soak')) {
        if($Report.tasks.consoleCalls -le 0 -or $null -eq $Report.tasks.consoleErrors -or $Report.tasks.consoleErrors -ne 0 -or @($Report.tasks.reconciliation).Count -eq 0 -or @($Report.tasks.exports).Count -eq 0 -or @(@($Report.tasks.reconciliation)+@($Report.tasks.exports)|Where-Object {$_.status -ne 'COMPLETE' -or $_.rows -le 0}).Count -gt 0){$taskProblems.Add('Mixed console calls, nonempty reconciliation or export completion failed')}
    }
    if(-not $Scaled -and $Protocol -notin @('Smoke','Manifest','Dataset')) {
        if($Report.manifest.d1CountersSatisfied -ne $true){$taskProblems.Add('D1 dataset not satisfied')}
        $taskDuration=if($Protocol -eq 'C3'){1800}elseif($Protocol -eq 'Soak'){14400}else{900}
        if($Report.durationSeconds -lt $taskDuration){$taskProblems.Add('Protocol duration too short')}
        if($Protocol -in @('C1','C3')) {
            $taskPayTarget=if($Protocol -eq 'C1'){300}else{150}
            $taskRefundTarget=if($Protocol -eq 'C1'){30}else{15}
            if($null -eq $Report.payment.completedPerArrivalSecondLower -or $Report.payment.completedPerArrivalSecondLower -lt $taskPayTarget -or $null -eq $Report.refund.completedPerArrivalSecondLower -or $Report.refund.completedPerArrivalSecondLower -lt $taskRefundTarget){$taskProblems.Add('Completed payment/refund throughput below target')}
            if($null -eq $Report.payment.successfulCompletionP99UpperMicros -or $Report.payment.successfulCompletionP99UpperMicros -gt 3000000 -or $Report.payment.httpP99Micros -gt 300000 -or $Report.refund.httpP99Micros -gt 300000){$taskProblems.Add('Payment/refund latency target failed')}
        }
        if($Protocol -in @('C2','C3')) {
            $taskQueryTarget=if($Protocol -eq 'C2'){2000}else{500}
            if($null -eq $Report.query.successfulPerArrivalSecond -or $Report.query.successfulPerArrivalSecond -lt $taskQueryTarget -or $null -eq $Report.query.httpP99Micros -or $Report.query.httpP99Micros -gt 200000){$taskProblems.Add('Query throughput/latency target failed')}
        }
        if($Protocol -eq 'C3' -and ($Report.tasks.batchCoveragePassed -ne $true -or $Report.tasks.listP95Micros -gt 500000)){$taskProblems.Add('Mixed batch/export/list coverage failed')}
        if($Protocol -eq 'Soak'){$taskProblems.Add('Hourly resource/queue trends and four-hour invariants require review alongside raw workload')}
    }
    [pscustomobject]@{localWorkloadPassed=($taskProblems.Count -eq 0);problems=@($taskProblems.ToArray());scaled=$Scaled;protocol=$Protocol;qualified=$false;referenceR1Certified=$false;note='Shared Docker Desktop host; resource isolation, warmup/repeats, observer calibration and mixed baseline comparison remain independent gates'}
}
