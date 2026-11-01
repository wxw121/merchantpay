[CmdletBinding()]
param([switch]$SkipBuild,[switch]$CheckOnly,[string]$EnvironmentFile='.env.native')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1') -EnvironmentFile $EnvironmentFile
foreach ($taskName in @('DATABASE_URL','DATABASE_USER','DATABASE_PASSWORD','OIDC_BASE_URL','OIDC_CLIENT_SECRET','MASTER_KEY','CHANNEL_TOKEN','CALLBACK_SECRET','METRICS_PASSWORD')) {
    $taskValue=[Environment]::GetEnvironmentVariable($taskName,'Process')
    if ([string]::IsNullOrWhiteSpace($taskValue) -or $taskValue.StartsWith('REPLACE_')) { throw ('Set '+$taskName+' in the native environment file. No process was started.') }
}
if (-not $env:DATABASE_URL.StartsWith('jdbc:mysql://')) { throw 'Native startup requires an explicit MySQL JDBC URL.' }
try { $taskIssuer=[uri]$env:OIDC_BASE_URL } catch { throw 'OIDC_BASE_URL must be an absolute HTTP(S) URL.' }
if (-not $taskIssuer.IsAbsoluteUri -or $taskIssuer.Scheme -notin @('http','https') -or $taskIssuer.UserInfo -or $taskIssuer.Query -or $taskIssuer.Fragment) { throw 'OIDC_BASE_URL must be an absolute HTTP(S) URL without credentials, query or fragment.' }
$env:OIDC_BASE_URL=$env:OIDC_BASE_URL.TrimEnd('/')
try { $taskMasterBytes=[Convert]::FromBase64String($env:MASTER_KEY) } catch { throw 'MASTER_KEY must be the existing Base64 AES key.' }
if ($taskMasterBytes.Length -ne 32) { throw 'MASTER_KEY must decode to the existing 32 byte AES key.' }
if (($env:PLATFORM_PORT -and $env:PLATFORM_PORT -ne '8080') -or ($env:SIMULATOR_PORT -and $env:SIMULATOR_PORT -ne '9091')) { throw 'This local console launcher uses platform 8080 and simulator 9091; use separate deployment profiles for custom ports.' }
$taskJava=Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/java25') -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } | Select-Object -First 1
$taskNode=Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/node24') -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'node.exe') } | Select-Object -First 1
if (-not $taskJava -or -not $taskNode) { throw 'Prepare the existing Java 25 and Node 24 toolchains before native startup.' }
$taskVite=Join-Path $taskRoot 'console/node_modules/vite/bin/vite.js'
if (-not (Test-Path -LiteralPath $taskVite)) { throw 'Console dependencies are missing. No automatic installation was attempted.' }
$taskState=Join-Path $taskRoot '.work/local-processes.json'
$taskRecords=[Collections.Generic.List[object]]::new()
if (Test-Path -LiteralPath $taskState) {
    foreach ($taskRecord in @(Get-Content -LiteralPath $taskState -Raw | ConvertFrom-Json)) {
        $taskExisting=Get-Process -Id $taskRecord.pid -ErrorAction SilentlyContinue
        if ($taskExisting -and $taskExisting.StartTime.ToUniversalTime().Ticks -eq $taskRecord.startedTicks -and $taskExisting.Path -eq $taskRecord.executable) { throw 'Recorded project processes are still running. Stop them before switching environment or starting again.' }
    }
}
if ($SkipBuild) {
    foreach ($taskService in @('platform','simulator')) {
        if (-not (Test-Path -LiteralPath (Join-Path $taskRoot ($taskService+'/target/'+$taskService+'-1.0.0-SNAPSHOT.jar')))) { throw ('Missing '+$taskService+' jar; build before using -SkipBuild.') }
    }
}
$taskListeningPorts=[Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners().Port
foreach ($taskPort in @(8080,9091,5173)) {
    if ($taskListeningPorts -contains $taskPort) { throw ('Local application port '+$taskPort+' is occupied. No process was started or stopped.') }
}
if ($CheckOnly) { Write-Output 'Native prerequisites checked locally. No services started, no network probes or Docker calls made.'; return }

$taskWork=Join-Path $taskRoot '.work'
New-Item -ItemType Directory -Path $taskWork -Force | Out-Null
$env:JAVA_TOOL_OPTIONS='-Xmx384m -XX:ActiveProcessorCount=1 -XX:TieredStopAtLevel=1'
$env:NODE_OPTIONS='--max-old-space-size=384'
$env:UV_THREADPOOL_SIZE='1'
$env:DATABASE_POOL_SIZE='8'
$env:FAST_WORKER_CONCURRENCY='4'
if (-not $env:WORKER_QUEUES) { $env:WORKER_QUEUES='ALL' }
if (-not $env:WORKERS_ENABLED) { $env:WORKERS_ENABLED='true' }
$env:MAXIMUM_API_INSTANCES='1'
if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot 'mvn.ps1') -MavenArguments @('-o','package','-DskipTests','-B','-ntp') *> (Join-Path $taskWork 'native-build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Native build failed; inspect .work/native-build.log. No services were started.' }
}
# The issuer may be local or remote. This readiness probe never starts an identity server.
$taskDiscovery=Invoke-RestMethod ($env:OIDC_BASE_URL.TrimEnd('/')+'/realms/merchantpay/.well-known/openid-configuration') -TimeoutSec 5
if ($taskDiscovery.issuer -ne ($env:OIDC_BASE_URL.TrimEnd('/')+'/realms/merchantpay')) { throw 'OIDC issuer does not match the native environment.' }
function Save-NativeProcesses {
    [IO.File]::WriteAllText($taskState,(ConvertTo-Json -InputObject @($taskRecords.ToArray()) -Depth 4),[Text.UTF8Encoding]::new($false))
}
function Start-NativeProcess([string]$Name,[string]$Executable,[string[]]$Arguments) {
    $taskProcess=Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskWork ($Name+'.stdout.log')) -RedirectStandardError (Join-Path $taskWork ($Name+'.stderr.log'))
    $taskRecords.Add([pscustomobject]@{name=$Name;pid=$taskProcess.Id;startedTicks=$taskProcess.StartTime.ToUniversalTime().Ticks;executable=$Executable})
    Save-NativeProcesses
}
try {
    $taskJavaOptions=@('-Xmx384m','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-jar')
    Start-NativeProcess 'simulator' (Join-Path $taskJava.FullName 'bin/java.exe') ($taskJavaOptions+@(('"'+(Join-Path $taskRoot 'simulator/target/simulator-1.0.0-SNAPSHOT.jar')+'"')))
    Start-NativeProcess 'platform' (Join-Path $taskJava.FullName 'bin/java.exe') ($taskJavaOptions+@(('"'+(Join-Path $taskRoot 'platform/target/platform-1.0.0-SNAPSHOT.jar')+'"'),'--spring.profiles.active=local'))
    Start-NativeProcess 'console' (Join-Path $taskNode.FullName 'node.exe') @(('"'+$taskVite+'"'),('"'+(Join-Path $taskRoot 'console')+'"'),'--host','127.0.0.1','--strictPort','--config',('"'+(Join-Path $taskRoot 'console/vite.config.ts')+'"'))
    [pscustomobject]@{mode='native';environmentFile=$taskEnvironmentPath;at=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskWork 'local-profile.json')
    $taskReady=$false
    for ($taskAttempt=0;$taskAttempt -lt 30;$taskAttempt++) {
        foreach ($taskRecord in $taskRecords) {
            $taskExisting=Get-Process -Id $taskRecord.pid -ErrorAction SilentlyContinue
            if (-not $taskExisting -or $taskExisting.StartTime.ToUniversalTime().Ticks -ne $taskRecord.startedTicks -or $taskExisting.Path -ne $taskRecord.executable) { throw ('Native '+$taskRecord.name+' exited during startup; inspect its log.') }
        }
        try {
            $taskPlatformUp=(Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 2).status -eq 'UP'
            $taskSimulatorUp=(Invoke-RestMethod 'http://127.0.0.1:9091/actuator/health' -TimeoutSec 2).status -eq 'UP'
            $taskConsoleUp=(Invoke-WebRequest 'http://127.0.0.1:5173/' -TimeoutSec 2).StatusCode -eq 200
            $taskReady=$taskPlatformUp -and $taskSimulatorUp -and $taskConsoleUp
            if ($taskReady) { break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $taskReady) { throw 'Native applications did not become ready; inspect the platform, simulator and console logs under .work.' }
    Write-Output 'Native console http://127.0.0.1:5173 | Platform http://127.0.0.1:8080. Infrastructure was not started or modified.'
} catch {
    foreach ($taskRecord in $taskRecords) {
        $taskExisting=Get-Process -Id $taskRecord.pid -ErrorAction SilentlyContinue
        if ($taskExisting -and $taskExisting.StartTime.ToUniversalTime().Ticks -eq $taskRecord.startedTicks -and $taskExisting.Path -eq $taskRecord.executable) { Stop-Process -Id $taskRecord.pid }
    }
    throw
}
