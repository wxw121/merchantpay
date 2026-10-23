[CmdletBinding()]
param([ValidateSet('SelfTest','Lcp','Feedback')][string]$Mode='SelfTest',[string]$BaseUrl='http://127.0.0.1:15173',[UInt64]$CpuMask=4026531840)
$ErrorActionPreference='Stop'
if(-not $IsWindows){throw 'This resource proof uses Windows Job Objects'}
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $taskRoot 'scripts/ProcessEnvironment.ps1')
if([Numerics.BitOperations]::PopCount($CpuMask) -ne 4 -or (($CpuMask -band [UInt64](Get-Process -Id $PID).ProcessorAffinity.ToInt64()) -ne $CpuMask)){throw 'Four allowed logical CPUs are required'}
$taskRun='client-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6)
$taskFolder=Join-Path $taskRoot ('.work/release/'+$taskRun);New-Item -ItemType Directory $taskFolder -Force|Out-Null
$taskSnapshot=Join-Path $taskFolder 'current.json';$taskOutput=Join-Path $taskFolder 'test.log'
$taskSaved=@{};foreach($taskName in @('WEB_PERF_RESOURCE_FILE','WEB_PERF_BASE_URL','RUN_WEB_PERF','RUN_CRITICAL_FEEDBACK','PLAYWRIGHT_HTML_REPORT')){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
$taskProcess=$null;$taskExit=1;$taskSamples=0;$taskVerified=$true;$taskMembers=@{}
try {
    Add-Type -Path (Join-Path $PSScriptRoot 'BudgetProcess.cs')
    $taskPwsh=(Get-Command pwsh).Source
    $env:WEB_PERF_RESOURCE_FILE=$taskSnapshot;$env:WEB_PERF_BASE_URL=$BaseUrl;$env:PLAYWRIGHT_HTML_REPORT=Join-Path $taskFolder 'html'
    $taskQuotedPwsh=$taskPwsh.Replace("'","''")
    if($Mode -eq 'SelfTest') {
        $taskCommand="Start-Process -FilePath '$taskQuotedPwsh' -ArgumentList @('-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 30') -WindowStyle Hidden | Out-Null; Start-Sleep -Seconds 2; exit 0"
    } else {
        $env:RUN_WEB_PERF=if($Mode -eq 'Lcp'){'1'}else{'0'};$env:RUN_CRITICAL_FEEDBACK=if($Mode -eq 'Feedback'){'1'}else{'0'}
        $taskTest=if($Mode -eq 'Lcp'){'web-performance.spec.ts'}else{'critical-feedback.spec.ts'}
        $taskCommand="& '"+(Join-Path $taskRoot 'scripts/pnpm.ps1').Replace("'","''")+"' -PnpmArguments @('exec','playwright','test','$taskTest','--output','"+(Join-Path $taskFolder 'results').Replace("'","''")+"') *> '"+$taskOutput.Replace("'","''")+"'; exit `$LASTEXITCODE"
    }
    $taskEncoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($taskCommand))
    $taskProcess=[BudgetProcess]::Start(('"'+$taskPwsh+'" -NoProfile -NonInteractive -EncodedCommand '+$taskEncoded),$taskRoot,$CpuMask,8GB)
    do {
        $taskSample=$taskProcess.Snapshot();$taskSamples++;$taskVerified=$taskVerified -and $taskSample.verified
        foreach($taskMember in $taskSample.members){$taskMembers[[string]$taskMember.pid]=$taskMember}
        $taskJson=$taskSample|ConvertTo-Json -Depth 8 -Compress
        [IO.File]::WriteAllText(($taskSnapshot+'.pending'),$taskJson,[Text.UTF8Encoding]::new($false));[IO.File]::Move(($taskSnapshot+'.pending'),$taskSnapshot,$true)
        Add-Content -LiteralPath (Join-Path $taskFolder 'resources.jsonl') -Value $taskJson
        if(-not $taskSample.verified){throw 'Process escaped declared job resource limits'}
        Start-Sleep -Milliseconds 200
    } while(-not $taskProcess.HasExited)
    $taskExit=[int]$taskProcess.ExitCode
    if($Mode -eq 'SelfTest' -and $taskMembers.Count -lt 2){throw 'Resource self-test did not observe the child process'}
    @{mode=$Mode;cpuMask=$CpuMask;logicalCpuBudget=4;jobCommittedMemoryLimitBytes=8GB;memorySemantics='Aggregate committed memory ceiling for test runner and browser process tree, not dedicated physical RAM';samples=$taskSamples;processes=@($taskMembers.Values);constraintsVerified=$taskVerified;exitCode=$taskExit;referenceR1Certified=$false}|ConvertTo-Json -Depth 8|Set-Content (Join-Path $taskFolder 'summary.json')
} finally {
    if($taskProcess){$taskProcess.Dispose()}
    Restore-ProcessEnvironment $taskSaved
}
Write-Output ('Resource-controlled '+$Mode+' evidence: '+$taskFolder)
if($taskExit -ne 0){if(Test-Path $taskOutput){Get-Content $taskOutput -Tail 20};throw 'Requested client verification failed'}
