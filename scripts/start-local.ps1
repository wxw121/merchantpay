[CmdletBinding()]
param([switch]$SkipBuild,[switch]$Performance,[switch]$Docker,[switch]$CheckOnly,[string]$EnvironmentFile)
$ErrorActionPreference = 'Stop'
if (-not $Docker) {
    if ($Performance) { throw 'The legacy performance profile requires explicit -Docker. Native startup uses bounded resources.' }
    $taskNativeArguments=@{SkipBuild=$SkipBuild;CheckOnly=$CheckOnly}
    if ($EnvironmentFile) { $taskNativeArguments.EnvironmentFile=$EnvironmentFile }
    & (Join-Path $PSScriptRoot 'start-native.ps1') @taskNativeArguments
    return
}
if ($CheckOnly -or $EnvironmentFile) { throw '-CheckOnly and -EnvironmentFile belong to the native launcher. Remove -Docker.' }
$taskRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'LocalPerformance.ps1')
$taskMode=if($Performance){'performance'}else{'normal'}
$taskProfilePath=Join-Path $taskRoot '.work/local-profile.json'
$taskCurrentMode=if(Test-Path $taskProfilePath){(Get-Content $taskProfilePath -Raw | ConvertFrom-Json).mode}else{'normal'}
$taskRunning=$false
$taskProcessPath=Join-Path $taskRoot '.work/local-processes.json'
if(Test-Path $taskProcessPath) {
    foreach($taskRecord in (Get-Content $taskProcessPath -Raw | ConvertFrom-Json)) {
        $taskExisting=Get-Process -Id $taskRecord.pid -ErrorAction SilentlyContinue
        if($taskExisting -and $taskExisting.StartTime.ToUniversalTime().Ticks -eq $taskRecord.startedTicks -and $taskExisting.Path -eq $taskRecord.executable) { $taskRunning=$true }
    }
}
Assert-LocalProfileChange $taskMode $taskCurrentMode $taskRunning
if($Performance) { Assert-PerformanceCpu ([Environment]::ProcessorCount) ((Get-Process -Id $PID).ProcessorAffinity.ToInt64()) }
New-Item -ItemType Directory -Path (Join-Path $taskRoot '.work') -Force | Out-Null
& (Join-Path $PSScriptRoot 'init-local.ps1')
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1') -EnvironmentFile '.env'
$taskDocker = (Get-Command docker.exe).Source
Push-Location $taskRoot
try {
    & (Join-Path $PSScriptRoot 'release/build-infrastructure.ps1')
    $taskComposeArguments=@('--env-file','.env','-f','infra/compose.yaml')
    if($Performance) { $taskComposeArguments+=@('-f','infra/compose.performance.yaml') }
    & $taskDocker compose @taskComposeArguments up -d mysql keycloak
    if ($LASTEXITCODE -ne 0) { throw 'Infrastructure startup failed.' }
    if (-not $SkipBuild) {
        & (Join-Path $PSScriptRoot 'stop-local.ps1')
        & (Join-Path $PSScriptRoot 'mvn.ps1') -MavenArguments @('package','-DskipTests','-B','-ntp')
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    }
    $taskJava = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools\java25') -Directory | Select-Object -First 1
    $taskNode = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools\node24') -Directory | Select-Object -First 1
    if (-not $taskJava -or -not $taskNode) { throw 'Run scripts/bootstrap.ps1 first.' }
    $taskOidcReady=$false
    for($taskAttempt=0;$taskAttempt -lt 90;$taskAttempt++) {
        try { $taskDiscovery=Invoke-RestMethod 'http://127.0.0.1:8180/realms/merchantpay/.well-known/openid-configuration' -TimeoutSec 2; if($taskDiscovery.issuer -eq 'http://127.0.0.1:8180/realms/merchantpay') { $taskOidcReady=$true;break } } catch { }
        Start-Sleep -Seconds 1
    }
    if(-not $taskOidcReady) { throw 'Project OIDC issuer is not ready. Inspect the project Keycloak logs.' }
    $taskRecords = [Collections.Generic.List[object]]::new()
    $taskState = Join-Path $taskRoot '.work\local-processes.json'
    if (Test-Path -LiteralPath $taskState) { foreach ($taskRecord in (Get-Content -LiteralPath $taskState -Raw | ConvertFrom-Json)) { $taskRecords.Add($taskRecord) } }
    [pscustomobject]@{mode=$taskMode;definition=if($Performance){Get-LocalPerformanceDefinition}else{$null};at=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json -Depth 4 | Set-Content $taskProfilePath
    function Start-ProjectProcess([string]$Name,[string]$Executable,[string[]]$Arguments) {
        foreach ($taskRecord in $taskRecords) {
            if ($taskRecord.name -eq $Name) {
                $taskExisting = Get-Process -Id $taskRecord.pid -ErrorAction SilentlyContinue
                if ($taskExisting -and $taskExisting.StartTime.ToUniversalTime().Ticks -eq $taskRecord.startedTicks -and $taskExisting.Path -eq $taskRecord.executable) { Write-Output ($Name + ' already running.'); return }
            }
        }
        $taskProcess = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskRoot ('.work\'+$Name+'.stdout.log')) -RedirectStandardError (Join-Path $taskRoot ('.work\'+$Name+'.stderr.log'))
        $taskRecords.Add([pscustomobject]@{name=$Name;pid=$taskProcess.Id;startedTicks=$taskProcess.StartTime.ToUniversalTime().Ticks;executable=$Executable})
        [IO.File]::WriteAllText($taskState,($taskRecords | ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
        if($Performance -and $Name -in @('platform','simulator')) { $taskProcess.ProcessorAffinity=[IntPtr]$(if($Name -eq 'platform'){15}else{48}) }
        Write-Output ($Name+' started (PID '+$taskProcess.Id+').')
    }
    $taskSimulatorOptions=@('-Xms128m','-Xmx768m'); $taskPlatformOptions=@('-Xms256m','-Xmx1536m')
    if($Performance) { $taskSimulatorOptions+='-XX:ActiveProcessorCount=2'; $taskPlatformOptions+='-XX:ActiveProcessorCount=4' }
    Start-ProjectProcess 'simulator' (Join-Path $taskJava.FullName 'bin\java.exe') ($taskSimulatorOptions+@('-jar',('"'+(Join-Path $taskRoot 'simulator\target\simulator-1.0.0-SNAPSHOT.jar')+'"')))
    $taskPlatformArguments=$taskPlatformOptions+@('-jar',('"'+(Join-Path $taskRoot 'platform\target\platform-1.0.0-SNAPSHOT.jar')+'"'),'--spring.profiles.active=local')
    if($Performance) { $taskPlatformArguments+='--merchantpay.tasks-fast-concurrency=96' }
    Start-ProjectProcess 'platform' (Join-Path $taskJava.FullName 'bin\java.exe') $taskPlatformArguments
    Start-ProjectProcess 'console' (Join-Path $taskNode.FullName 'node.exe') @(('"'+(Join-Path $taskRoot 'console\node_modules\vite\bin\vite.js')+'"'),('"'+(Join-Path $taskRoot 'console')+'"'),'--host','127.0.0.1','--config',('"'+(Join-Path $taskRoot 'console\vite.config.ts')+'"'))
    [IO.File]::WriteAllText($taskState,($taskRecords | ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    $taskPlatformReady=$false
    for($taskAttempt=0;$taskAttempt -lt 60;$taskAttempt++) {
        try { $taskHealth=Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 2; if($taskHealth.status -eq 'UP') { $taskPlatformReady=$true;break } } catch { }
        Start-Sleep -Seconds 1
    }
    if(-not $taskPlatformReady) { throw 'Project platform did not become healthy. Inspect .work/platform.stdout.log and .work/platform.stderr.log.' }
    Write-Output 'Console http://127.0.0.1:5173 | Platform http://127.0.0.1:8080 | Keycloak http://127.0.0.1:8180'
} finally { Pop-Location }
