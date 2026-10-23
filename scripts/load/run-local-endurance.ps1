[CmdletBinding()]
param([ValidateRange(30,1800)][int]$MixedSeconds=1800,[ValidateRange(60,14400)][int]$SoakSeconds=14400,[ValidateRange(1,10000)][int]$SeedPayments=3000)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $taskRoot 'scripts/ProcessEnvironment.ps1')
$taskId='endurance-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')
$taskFolder=Join-Path $taskRoot ('.work/acceptance/'+$taskId)
New-Item -ItemType Directory $taskFolder -Force|Out-Null
Push-Location $taskRoot
$taskSaved=@{};foreach($taskName in @('LOAD_NOTIFICATION_URL','LOAD_CONSOLE_COOKIE','LOAD_CSRF_TOKEN','LOAD_BATCH_MERCHANT','LOAD_BATCH_DAY','LOAD_BATCH_VERSION','LOAD_CONSOLE_INTERVAL_MILLIS')){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
$taskProcess=$null;$taskReports=@();$taskStarted=[DateTimeOffset]::UtcNow
try {
    Add-Type -Path (Join-Path $taskRoot 'scripts/browser/BudgetProcess.cs')
    $env:LOAD_NOTIFICATION_URL='http://simulator:9091/merchant-webhook'
    $env:LOAD_BATCH_MERCHANT='merchant-a';$env:LOAD_BATCH_DAY=[DateTime]::UtcNow.ToString('yyyy-MM-dd');$env:LOAD_CONSOLE_INTERVAL_MILLIS='1000'
    function Invoke-EnduranceStage([string]$Name,[string]$Arguments) {
        $taskSession=Get-Content '.data/acceptance/console-session.json' -Raw|ConvertFrom-Json
        $env:LOAD_CONSOLE_COOKIE=$taskSession.cookie;$env:LOAD_CSRF_TOKEN=$taskSession.csrf
        $env:LOAD_BATCH_VERSION=[string][DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        $taskLog=Join-Path $taskFolder ($Name+'.log')
        $taskCommand="& './scripts/load/run-v1.ps1' -CredentialsFile '.data/container/api-credentials.json' -BaseUrl 'http://127.0.0.1:18080' -SimulatorUrl 'http://127.0.0.1:19091' -DatabaseUrl 'jdbc:mysql://127.0.0.1:13307/merchantpay?connectionTimeZone=UTC' "+$Arguments+" *> '"+$taskLog.Replace("'","''")+"'; exit `$LASTEXITCODE"
        $taskEncoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($taskCommand))
        $taskStageStart=[DateTimeOffset]::UtcNow
        $taskProcess=[BudgetProcess]::Start(('"'+(Get-Command pwsh).Source+'" -NoProfile -NonInteractive -EncodedCommand '+$taskEncoded),$taskRoot,192,2GB)
        try {
            do {
                $taskBudget=$taskProcess.Snapshot()
                if(-not $taskBudget.verified){throw 'Load process resource verification failed'}
                $taskDrive=Get-PSDrive D
                $taskRuntime=& docker stats --no-stream --format '{{json .}}' merchantpay-container-v1-platform-1 merchantpay-container-v1-simulator-1 merchantpay-container-v1-mysql-1 merchantpay-storage-v1-s3-1
                if($LASTEXITCODE -ne 0){throw 'Endurance resource sampler failed'}
                $taskFacts=& docker exec merchantpay-container-v1-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u merchantpay -N merchantpay -e "SELECT (SELECT COUNT(*) FROM outbox_tasks WHERE status<>CHAR(68,79,78,69)),(SELECT COALESCE(SUM(inflight),0) FROM payment_admission),(SELECT COUNT(*) FROM (SELECT journal_id FROM ledger_entries GROUP BY journal_id HAVING SUM(CASE WHEN side=CHAR(68,69,66,73,84) THEN amount_minor ELSE -amount_minor END)<>0) bad)"'
                if($LASTEXITCODE -ne 0){throw 'Endurance invariant sampler failed'}
                $taskValues=$taskFacts.Trim().Split("`t")
                @{at=[DateTimeOffset]::UtcNow;stage=$Name;elapsedSeconds=([DateTimeOffset]::UtcNow-$taskStageStart).TotalSeconds;freeBytes=$taskDrive.Free;unfinishedTasks=[long]$taskValues[0];admission=[long]$taskValues[1];unbalancedJournals=[long]$taskValues[2];budget=$taskBudget;docker=@($taskRuntime|ForEach-Object {$_|ConvertFrom-Json})}|ConvertTo-Json -Depth 8 -Compress|Add-Content (Join-Path $taskFolder 'resources.jsonl')
                if($taskDrive.Free -lt 10GB -or [long]$taskValues[2] -ne 0){throw 'Endurance stopped: disk reserve or financial invariant violated'}
                for($taskTick=0;$taskTick -lt 60 -and -not $taskProcess.HasExited;$taskTick++){Start-Sleep -Seconds 1}
            } while(-not $taskProcess.HasExited)
            if($taskProcess.ExitCode -ne 0){throw ('Endurance '+$Name+' workload failed; see '+$taskLog)}
            return @{stage=$Name;started=$taskStageStart;ended=[DateTimeOffset]::UtcNow;exitCode=$taskProcess.ExitCode;log=$taskLog}
        } finally {if($taskProcess){$taskProcess.Dispose();$taskProcess=$null}}
    }
    $taskReports+=Invoke-EnduranceStage 'seed' ('-Protocol Dataset -DatasetId '+$taskId+' -Payments '+$SeedPayments+' -PayRate 10')
    $taskReports+=Invoke-EnduranceStage 'mixed' ('-Protocol C3 -Scaled -Seconds '+$MixedSeconds+' -PayRate 5 -RefundRate 1 -QueryRate 20')
    $taskReports+=Invoke-EnduranceStage 'soak' ('-Protocol Soak -Scaled -Seconds '+$SoakSeconds+' -PayRate 10 -RefundRate 1 -QueryRate 50')
    $taskReports+=Invoke-EnduranceStage 'final' '-Protocol Manifest'
    $taskFacts=Get-Content (Join-Path $taskFolder 'final.log')|Where-Object { $_.StartsWith('{') }|Select-Object -Last 1|ConvertFrom-Json
    if($taskFacts.moneyInvariantsPassed -ne $true -or $taskFacts.deliveryConverged -ne $true){throw 'Final whole-database money, queue and HTTP delivery evidence did not converge'}
    @{started=$taskStarted;ended=[DateTimeOffset]::UtcNow;mixedSeconds=$MixedSeconds;soakSeconds=$SoakSeconds;scaled=$true;consoleWorkers=15;consoleIntervalMillis=1000;fullDurations=($MixedSeconds -eq 1800 -and $SoakSeconds -eq 14400);stages=$taskReports;localWorkloadPassed=$true;referenceR1Certified=$false}|ConvertTo-Json -Depth 8|Set-Content (Join-Path $taskFolder 'summary.json')
    Write-Output ('Local endurance evidence: '+$taskFolder)
} catch {
    @{at=[DateTimeOffset]::UtcNow;started=$taskStarted;completedStages=$taskReports;localWorkloadPassed=$false;referenceR1Certified=$false;reason=$_.Exception.Message}|ConvertTo-Json -Depth 8|Set-Content (Join-Path $taskFolder 'failure.json')
    throw
} finally {
    if($taskProcess){$taskProcess.Dispose()}
    Restore-ProcessEnvironment $taskSaved;Pop-Location
}
