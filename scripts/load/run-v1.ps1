[CmdletBinding()]
param([ValidateSet('Smoke','Manifest','Truth','Dataset','C1','C2','C3','Soak')][string]$Protocol='Smoke',[switch]$Scaled,[ValidateRange(1,14400)][int]$Seconds=15,[ValidateRange(0,10000)][int]$PayRate=5,[ValidateRange(0,10000)][int]$RefundRate=1,[ValidateRange(0,10000)][int]$QueryRate=10,[ValidateRange(1,1000000)][int]$Payments=1000,[ValidatePattern('^[a-zA-Z0-9-]{1,40}$')][string]$DatasetId='v1-dataset',[ValidatePattern('^[a-zA-Z0-9-]{1,60}$')][string]$RunId,[string]$CredentialsFile='.data/platform/local-api-credentials.json',[string]$BaseUrl='http://127.0.0.1:8080',[string]$SimulatorUrl='http://127.0.0.1:9091',[string]$DatabaseUrl,[string]$NotificationUrl)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $taskRoot
$taskLock=$null
try {
    $taskLock=[IO.File]::Open((Join-Path $taskRoot '.work/performance-baseline.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    . scripts/Import-LocalEnvironment.ps1
    if($DatabaseUrl){$env:DATABASE_URL=$DatabaseUrl}
    if($NotificationUrl){$env:LOAD_NOTIFICATION_URL=$NotificationUrl}
    elseif($BaseUrl -ne 'http://127.0.0.1:8080' -and $Protocol -notin @('Manifest','Truth') -and ($PayRate -gt 0 -or (-not $Scaled -and $Protocol -ne 'Smoke')) -and -not $env:LOAD_NOTIFICATION_URL){throw 'Payment load against a non-default platform URL requires -NotificationUrl or LOAD_NOTIFICATION_URL matching that deployment'}
    . scripts/load/V1Acceptance.ps1
    $env:LOAD_SCALED=([bool]($Scaled -or $Protocol -eq 'Smoke')).ToString().ToLowerInvariant()
    $taskKeys=Get-Content -LiteralPath $CredentialsFile -Raw | ConvertFrom-Json
    $env:LOAD_CREDENTIALS=($taskKeys | ForEach-Object {$_.merchantId+"`t"+$_.keyId+"`t"+$_.secret}) -join "`n"
    $env:LOAD_BASE_URL=$BaseUrl;$env:LOAD_SIMULATOR_URL=$SimulatorUrl;$env:LOAD_SEED='20261019'
    if(-not $env:LOAD_NOTIFICATION_URL){$env:LOAD_NOTIFICATION_URL=$env:LOCAL_WEBHOOK}
    $taskRun='accept-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
    $env:LOAD_OUTPUT=Join-Path $taskRoot ('.work/acceptance/'+$taskRun)
    if($Protocol -eq 'Dataset'){$env:LOAD_OUTPUT=Join-Path $taskRoot ('.work/acceptance/datasets/'+$DatasetId)}
    New-Item -ItemType Directory -Path $env:LOAD_OUTPUT -Force | Out-Null
    $taskJava=(Get-ChildItem '.tools/java25' -Directory | Select-Object -First 1).FullName
    $taskDriver=(Get-ChildItem '.tools/m2/com/mysql/mysql-connector-j' -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
    $taskClasses=Join-Path $taskRoot '.work/acceptance-classes'
    New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
    & (Join-Path $taskJava 'bin/javac.exe') -d $taskClasses -cp $taskDriver scripts/load/LocalLoad.java scripts/load/LoadMeasurements.java scripts/load/AcceptanceLoad.java
    if($LASTEXITCODE -ne 0){throw 'Acceptance driver compilation failed'}
    $taskCp=$taskClasses+[IO.Path]::PathSeparator+$taskDriver
    & (Join-Path $taskJava 'bin/java.exe') -Xmx1536m -cp $taskCp AcceptanceLoad manifest initial
    if($LASTEXITCODE -ne 0){throw 'Initial financial manifest failed'}
    $taskInitial=Get-Content (Join-Path $env:LOAD_OUTPUT 'initial.json') -Raw | ConvertFrom-Json
    if($Protocol -eq 'Manifest'){return}
    if(($Scaled -or $Protocol -eq 'Smoke') -and ($PayRate+$RefundRate+$QueryRate) -le 0){throw 'At least one nonzero arrival rate is required'}
    $taskProfileReady=$false
    for($taskAttempt=0;$taskAttempt -lt 20;$taskAttempt++) {
        try {$taskDescriptor=Invoke-RestMethod ($SimulatorUrl+'/acceptance/profile') -Headers @{'X-Channel-Token'=$env:CHANNEL_TOKEN} -TimeoutSec 2;if($taskDescriptor.Trim() -eq "standard-v1`t20261019"){$taskProfileReady=$true;break}}catch{}
        Start-Sleep -Seconds 1
    }
    if(-not $taskProfileReady){throw 'Matching seeded standard-v1 simulator is not ready; no workload sent'}
    if($Protocol -eq 'Truth') {
        if(-not $RunId){throw 'Truth recheck requires the original -RunId'}
        & (Join-Path $taskJava 'bin/java.exe') -Xmx512m -cp $taskCp AcceptanceLoad truth $RunId
        if($LASTEXITCODE -ne 0){throw 'Channel truth recheck failed'}
        return
    }
    if($Protocol -eq 'Dataset') {
        & (Join-Path $taskJava 'bin/java.exe') -Xmx1536m -cp $taskCp AcceptanceLoad dataset $DatasetId $Payments ([math]::Max(1,$PayRate))
        if($LASTEXITCODE -ne 0){throw 'Dataset generation incomplete; stable business keys retained for resume'}
        return
    }
    if(-not $Scaled -and $Protocol -ne 'Smoke' -and $taskInitial.d1CountersSatisfied -ne $true){throw 'Formal workload requires verified D1; use -Scaled for explicitly labelled development measurements'}
    $taskBatch=($Protocol -in @('C3','Soak'));$taskSoak=($Protocol -eq 'Soak')
    if(-not $Scaled -and $Protocol -ne 'Smoke') {
        & (Join-Path $taskJava 'bin/java.exe') -Xmx1536m -cp $taskCp AcceptanceLoad warmup ($taskRun+'-warm') 600 31 0 0 false false
        if($LASTEXITCODE -ne 0){throw 'Warmup failed; no measured pass is possible'}
        switch($Protocol) {
            'C1' {$Seconds=900;$PayRate=307;$RefundRate=31;$QueryRate=0}
            # Arrival headroom covers ordinary tail RTT; the successful-QPS verdict stays 2000/500.
            'C2' {$Seconds=900;$PayRate=0;$RefundRate=0;$QueryRate=2050}
            'C3' {$Seconds=1800;$PayRate=154;$RefundRate=16;$QueryRate=513;$taskBatch=$true}
            'Soak' {$Seconds=14400;$PayRate=154;$RefundRate=16;$QueryRate=513;$taskBatch=$true;$taskSoak=$true}
        }
    }
    $taskRounds=if($Scaled -or $Protocol -eq 'Smoke' -or $Protocol -eq 'Soak'){1}else{3}
    $taskVerdicts=@()
    for($taskRound=1;$taskRound -le $taskRounds;$taskRound++) {
        $taskStage=$taskRun+'-r'+$taskRound
        & (Join-Path $taskJava 'bin/java.exe') -Xmx1536m -cp $taskCp AcceptanceLoad $Protocol $taskStage $Seconds $PayRate $RefundRate $QueryRate ($taskBatch.ToString().ToLowerInvariant()) ($taskSoak.ToString().ToLowerInvariant())
        $taskExit=$LASTEXITCODE
        $taskReportPath=Join-Path $env:LOAD_OUTPUT ($taskStage+'.json')
        if(-not (Test-Path $taskReportPath)){throw 'No complete workload report; inspect failure artifact'}
        $taskReport=Get-Content $taskReportPath -Raw | ConvertFrom-Json
        $taskVerdict=Get-V1AcceptanceVerdict $taskReport $Protocol ([bool]($Scaled -or $Protocol -eq 'Smoke'))
        $taskVerdicts+=@($taskVerdict)
        @{run=$taskRun;rounds=$taskVerdicts;qualified=$false;referenceR1Certified=$false;environment='Docker Desktop shared host'} | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $env:LOAD_OUTPUT 'protocol.json')
        if($taskExit -ne 0 -or -not $taskVerdict.localWorkloadPassed){throw ('Workload gate failed; evidence: '+$taskReportPath)}
    }
    Write-Output ('Local protocol evidence: '+$env:LOAD_OUTPUT+'; no R1 certification')
} finally {Remove-Item Env:LOAD_CREDENTIALS -ErrorAction SilentlyContinue;if($taskLock){$taskLock.Dispose()};Pop-Location}
