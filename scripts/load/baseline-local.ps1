[CmdletBinding()]
param([ValidateRange(600,1800)][int]$WarmupSeconds=600,[ValidateRange(1,100)][int]$WarmupRate=30,[ValidateRange(1,400)][int]$Rate=50,[ValidateRange(60,300)][int]$Seconds=60,[ValidateRange(3,5)][int]$Rounds=3,[switch]$CheckOnly,[switch]$CalibrateObserver)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $taskRoot 'scripts/LocalPerformance.ps1')
. (Join-Path $taskRoot 'scripts/Import-LocalEnvironment.ps1')
$taskDefinition=Get-LocalPerformanceDefinition
$taskJava=(Get-ChildItem (Join-Path $taskRoot '.tools/java25') -Directory | Select-Object -First 1).FullName
$taskDriver=(Get-ChildItem (Join-Path $taskRoot '.tools/m2/com/mysql/mysql-connector-j') -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$taskClasses=Join-Path $taskRoot '.work/baseline-classes'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
$taskClasspath=$taskClasses+[IO.Path]::PathSeparator+$taskDriver
$taskId='baseline-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$taskDirectory=Join-Path $taskRoot ('.work/performance/'+$taskId)
New-Item -ItemType Directory -Path $taskDirectory | Out-Null
$taskLock=$null; $taskDiagProcesses=[Collections.Generic.List[object]]::new()
Push-Location $taskRoot
try {
    $taskLock=[IO.File]::Open((Join-Path $taskRoot '.work/performance-baseline.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    if((Get-Content .work/local-profile.json -Raw | ConvertFrom-Json).mode -ne 'performance') { throw 'Start with scripts/start-local.ps1 -Performance -SkipBuild after stopping the project.' }
    Assert-PerformanceCpu ([Environment]::ProcessorCount) ((Get-Process -Id $PID).ProcessorAffinity.ToInt64())
    & (Join-Path $taskJava 'bin/javac.exe') -cp $taskDriver -d $taskClasses scripts/load/LocalLoad.java scripts/load/LoadMeasurements.java scripts/load/LocalDiagnosis.java scripts/load/RuntimeSnapshot.java
    if($LASTEXITCODE -ne 0){throw 'Baseline compilation failed'}
    $taskToolPaths=@('infra/compose.yaml','infra/compose.performance.yaml','scripts/LocalPerformance.ps1','scripts/start-local.ps1','scripts/load/run-local.ps1','scripts/load/baseline-local.ps1','scripts/load/LocalLoad.java','scripts/load/LoadMeasurements.java','scripts/load/LocalDiagnosis.java','scripts/load/RuntimeSnapshot.java')
    $taskToolHashes=@(foreach($taskPath in $taskToolPaths){[pscustomobject]@{path=$taskPath;sha256=(Get-FileHash $taskPath -Algorithm SHA256).Hash}})
    $taskToolHashes | ConvertTo-Json | Set-Content (Join-Path $taskDirectory 'tools.json')
    function Snapshot([string]$Label) {
        $taskProfile=Get-Content .work/local-profile.json -Raw | ConvertFrom-Json
        if($taskProfile.mode -ne 'performance') { throw 'Performance profile changed' }
        foreach($taskTool in $taskToolHashes){if((Get-FileHash $taskTool.path -Algorithm SHA256).Hash -ne $taskTool.sha256){throw 'Measurement source/configuration changed during the protocol'}}
        $taskNative=@(foreach($taskName in @('platform','simulator')) {
            $taskRecord=Get-Content .work/local-processes.json -Raw | ConvertFrom-Json | Where-Object name -eq $taskName | Select-Object -Last 1
            $taskProcess=Get-Process -Id $taskRecord.pid -ErrorAction Stop
            $taskMask=if($taskName -eq 'platform'){15}else{48}
            $taskCpu=if($taskName -eq 'platform'){4}else{2}
            $taskHeap=if($taskName -eq 'platform'){1536MB}else{768MB}
            if($taskProcess.StartTime.ToUniversalTime().Ticks -ne $taskRecord.startedTicks -or $taskProcess.Path -ne $taskRecord.executable -or $taskProcess.ProcessorAffinity.ToInt64() -ne $taskMask){throw 'Native process identity/CPU affinity drift'}
            $taskFlags=& (Join-Path $taskJava 'bin/jcmd.exe') $taskProcess.Id VM.flags
            if($LASTEXITCODE -ne 0 -or ($taskFlags -join ' ') -notmatch ('-XX:ActiveProcessorCount='+$taskCpu+'\b') -or ($taskFlags -join ' ') -notmatch ('-XX:MaxHeapSize='+$taskHeap+'\b')) { throw 'Native JVM resource budget mismatch' }
            [pscustomobject]@{name=$taskName;pid=$taskRecord.pid;startedTicks=$taskRecord.startedTicks;affinity=$taskMask;cpu=$taskCpu;maxHeapBytes=$taskHeap;workingSetBytes=$taskProcess.WorkingSet64}
        })
        $taskContainer=docker ps --filter label=com.docker.compose.project=merchantpay-v1 --filter label=com.docker.compose.service=mysql --format '{{.ID}}'
        if($LASTEXITCODE -ne 0 -or -not $taskContainer) { throw 'MySQL container missing' }
        $taskContainerInfo=docker inspect --format '{{.Id}}|{{.Image}}|{{.HostConfig.Memory}}|{{.HostConfig.NanoCpus}}|{{.RestartCount}}' $taskContainer
        if($LASTEXITCODE -ne 0) { throw 'MySQL resource inspection failed' }
        $taskFields=$taskContainerInfo.Split('|')
        if([long]$taskFields[2] -ne $taskDefinition.mysqlMemoryBytes -or [long]$taskFields[3] -ne 4000000000) { throw 'MySQL resource caps differ from D0 profile' }
        & (Join-Path $taskJava 'bin/java.exe') -Xmx256m -cp $taskClasspath RuntimeSnapshot > (Join-Path $taskDirectory ($Label+'.runtime.json'))
        if($LASTEXITCODE -ne 0) { throw 'Read-only runtime probe failed' }
        $taskRuntime=Get-Content (Join-Path $taskDirectory ($Label+'.runtime.json')) -Raw | ConvertFrom-Json
        $taskConfig=$taskRuntime.mysql.config
        if($taskConfig.bufferPoolBytes -ne 1GB -or $taskConfig.flushOnCommit -ne 1 -or $taskConfig.syncBinlog -ne 1) { throw 'MySQL cache/durability drift' }
        if($taskRuntime.unfinishedTasks -ne 0 -or @($taskRuntime.admission).Count -ne 16 -or @($taskRuntime.admission | Where-Object { $_.inflight -ne 0 -or $_.actual -ne 0 }).Count) { throw 'Baseline boundary has unfinished work or capacity drift' }
        $taskOs=Get-CimInstance Win32_OperatingSystem
        $taskFingerprint=[ordered]@{definition=$taskDefinition;platformJar=(Get-FileHash platform/target/platform-1.0.0-SNAPSHOT.jar -Algorithm SHA256).Hash;simulatorJar=(Get-FileHash simulator/target/simulator-1.0.0-SNAPSHOT.jar -Algorithm SHA256).Hash;native=@($taskNative | Select-Object name,pid,startedTicks,affinity,cpu,maxHeapBytes);mysqlContainer=$taskContainerInfo;mysql=$taskConfig;instruments=$taskRuntime.mysql.instruments;consumers=$taskRuntime.mysql.consumers}
        $taskSignature=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($taskFingerprint | ConvertTo-Json -Depth 15 -Compress))))
        [pscustomobject]@{signature=$taskSignature;fingerprint=$taskFingerprint;native=$taskNative;host=[pscustomobject]@{logicalCpu=[Environment]::ProcessorCount;freeMiB=[math]::Round($taskOs.FreePhysicalMemory/1024);totalMiB=[math]::Round($taskOs.TotalVisibleMemorySize/1024)}} | ConvertTo-Json -Depth 18 | Set-Content (Join-Path $taskDirectory ($Label+'.resources.json'))
        $taskSignature
    }
    function Drain([string]$Label) {
        $taskDeadline=[DateTime]::UtcNow.AddSeconds(180); $taskAttempts=[Collections.Generic.List[object]]::new()
        do {
            $taskChannels=@(foreach($taskChannel in @('MOCK_A','MOCK_B')) { Invoke-RestMethod ('http://127.0.0.1:9091/channels/'+$taskChannel+'/health') -Headers @{'X-Channel-Token'=$env:CHANNEL_TOKEN} -TimeoutSec 5 })
            $taskClean=@($taskChannels | Where-Object { $_.pendingCallbacks -ne 0 -or $_.pendingPayments -ne 0 }).Count -eq 0
            $taskAttempts.Add([pscustomobject]@{at=[DateTime]::UtcNow.ToString('o');channels=$taskChannels})
            $taskAttempts.ToArray() | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $taskDirectory ($Label+'.channels.json'))
            if($taskClean){return}; Start-Sleep -Seconds 2
        } while([DateTime]::UtcNow -lt $taskDeadline)
        throw 'Channel backlog did not drain within180seconds'
    }
    function ApiMetrics([string]$Label) {
        $taskAuth='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('metrics:'+$env:METRICS_PASSWORD))
        $taskMetrics=Invoke-WebRequest 'http://127.0.0.1:8080/actuator/prometheus' -Headers @{Authorization=$taskAuth} -TimeoutSec 5
        $taskLines=@($taskMetrics.Content -split "`n" | Where-Object { $_ -match '^merchantpay_api_(unavailable|stage)_' })
        $taskLines | Set-Content (Join-Path $taskDirectory ($Label+'.prom'))
        Assert-ApiAvailabilityMetrics $taskLines
    }
    function Load([string]$Label,[int]$RunRate,[int]$RunSeconds,[switch]$Diagnose,[string]$ObserverMode='full') {
        Drain ($Label+'-before')
        $taskSignature=Snapshot ($Label+'-before')
        $taskDiag=$null
        if($Diagnose) {
            $taskDiag=Start-Process (Join-Path $taskJava 'bin/java.exe') -ArgumentList @('-Xmx256m','-cp',('"'+$taskClasspath+'"'),'LocalDiagnosis',($RunSeconds+30)) -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskDirectory ($Label+'.diagnostic.log')) -RedirectStandardError (Join-Path $taskDirectory ($Label+'.diagnostic.err.log'))
            $taskDiagProcesses.Add($taskDiag); Start-Sleep -Seconds 3
            if($taskDiag.HasExited){throw 'Diagnostic exited before load'}
        }
        if($CalibrateObserver){ApiMetrics ($Label+'.metrics-before')}
        Write-Host ('Starting '+$Label+': '+$RunRate+'/s for'+$RunSeconds+'seconds; observer='+$ObserverMode+'; '+$taskDirectory)
        & scripts/load/run-local.ps1 -Performance -Scenario pay -Rate $RunRate -SecondsOrPayments $RunSeconds -ObserverMode $ObserverMode *> (Join-Path $taskDirectory ($Label+'.load.log'))
        $taskLoadExit=$LASTEXITCODE
        if($taskDiag) {
            if(-not $taskDiag.WaitForExit(60000) -or $taskDiag.ExitCode -ne 0){throw 'Diagnostic did not finish successfully'}
            $taskText=Get-Content (Join-Path $taskDirectory ($Label+'.diagnostic.log')) -Raw
            if($taskText -notmatch '(diagnosis-\d+-[0-9a-f]{8})'){throw 'Diagnostic output missing'}
            $taskDiagnosticId=$Matches[1]
        }
        $taskReport=Get-Content (Join-Path $taskDirectory ($Label+'.load.log')) | Where-Object { $_.StartsWith('{') } | Select-Object -Last 1 | ConvertFrom-Json
        if(-not $taskReport -or -not $taskReport.moneyInvariantsPassed -or $taskReport.unfinishedAcceptedPayments -ne 0){throw 'Load lacks money correctness evidence'}
        Drain ($Label+'-after')
        $taskAfter=Snapshot ($Label+'-after')
        if($CalibrateObserver){ApiMetrics ($Label+'.metrics-after')}
        if($taskSignature -ne $taskAfter){throw 'Runtime/resources changed during the phase'}
        $taskReport | Add-Member -NotePropertyName profileSignature -NotePropertyValue $taskSignature
        $taskReport | Add-Member -NotePropertyName loadExit -NotePropertyValue $taskLoadExit
        if($taskDiag) {
            $taskBase='.work/performance/'+$taskDiagnosticId
            $taskSamples=@(Get-Content ($taskBase+'.jsonl') | ConvertFrom-Json)
            $taskIoBefore=Get-Content ($taskBase+'.io-before.json') -Raw | ConvertFrom-Json
            $taskIoAfter=Get-Content ($taskBase+'.io-after.json') -Raw | ConvertFrom-Json
            $taskIoDelta=Get-Content ($taskBase+'.io-delta.json') -Raw | ConvertFrom-Json
            if(-not $taskIoDelta.valid -or @($taskSamples | Where-Object sampleError).Count -or @($taskSamples | Where-Object metricsStatus -ne 200).Count -or [DateTimeOffset]$taskIoBefore.finishedAt -gt [DateTimeOffset]$taskReport.primaryPhaseStarted -or [DateTimeOffset]$taskIoAfter.startedAt -lt [DateTimeOffset]$taskReport.generatedAt){throw 'Invalid or incomplete diagnostic coverage'}
            $taskReport | Add-Member -NotePropertyName diagnosticId -NotePropertyValue $taskDiagnosticId
        }
        $taskReport | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $taskDirectory ($Label+'.report.json'))
        Write-Host ('Finished '+$Label+'; completed TPS '+$taskReport.completedPerArrivalSecondLower+'..'+$taskReport.completedPerArrivalSecondUpper+'; strictPass='+$taskReport.correctnessPassed)
        return $taskReport
    }
    # Progress is emitted via Write-Host so only structured reports flow into assignments.
    $taskInitial=Snapshot 'initial'
    if($CheckOnly) {
        [pscustomobject]@{id=$taskId;profileSignature=$taskInitial;preflightPassed=$true;qualified=$false;reason='Read-only preflight only; warmup and measured rounds were not run'} | ConvertTo-Json | Set-Content (Join-Path $taskDirectory 'preflight.json')
        Write-Output ('Read-only baseline preflight passed: '+$taskDirectory); return
    }
    $taskWarmup=Load 'warmup' $WarmupRate $WarmupSeconds
    $taskMeasured=if($CalibrateObserver){
        $taskOrder=@('full','after','after','full')
        @(foreach($taskIndex in 0..3){Load ('round-'+($taskIndex+1)) $Rate $Seconds -Diagnose -ObserverMode $taskOrder[$taskIndex]})
    }else{@(foreach($taskRound in 1..$Rounds){Load ('round-'+$taskRound) $Rate $Seconds -Diagnose})}
    $taskVerdict=Get-LocalBaselineVerdict $taskWarmup $taskMeasured
    $taskCalibration=if($CalibrateObserver){Get-ObserverCalibrationVerdict $taskWarmup $taskMeasured}else{$null}
    [pscustomobject]@{id=$taskId;at=[DateTime]::UtcNow.ToString('o');observerCalibration=[bool]$CalibrateObserver;calibration=$taskCalibration;definition=$taskDefinition;warmup=$taskWarmup;rounds=$taskMeasured;verdict=$taskVerdict;limits=@('D0 shared host/WSL/storage, caps are not exclusive CPUs','Native memory limits are JVM heaps; OS/native overhead sampled, not hard capped','Single merchant/current-day/mock success workload; not fullR1/D1 distribution','Data grows between rounds; dataset counters retained; no identical restored dataset claim','Observer after controls verify final finances only, no completion TPS/latency; ABBA does not establish causality on a shared host')} | ConvertTo-Json -Depth 22 | Set-Content (Join-Path $taskDirectory 'summary.json')
    Write-Output ('Baseline evidence: '+(Join-Path $taskDirectory 'summary.json'))
    if(-not $CalibrateObserver -and -not $taskVerdict.qualified){throw 'D0 baseline did not qualify; all rounds and reasons retained'}
    if($CalibrateObserver -and -not $taskCalibration.dataValid){throw 'Observer calibration contains invalid data; all rounds and reasons retained'}
} catch {
    [pscustomobject]@{at=[DateTime]::UtcNow.ToString('o');error=$_.Exception.Message;qualified=$false} | ConvertTo-Json | Set-Content (Join-Path $taskDirectory 'failure.json')
    throw
} finally {
    foreach($taskProcess in $taskDiagProcesses) { if(-not $taskProcess.HasExited){$taskProcess.Kill()} }
    if($taskLock){$taskLock.Dispose()}; Pop-Location
}
