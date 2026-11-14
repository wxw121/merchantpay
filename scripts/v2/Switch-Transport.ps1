[CmdletBinding()]
param([Parameter(Mandatory)][ValidateSet('POLLING','CDC')][string]$Expected,
      [Parameter(Mandatory)][ValidateSet('POLLING','CDC')][string]$Next,
      [switch]$WritersStopped)
$ErrorActionPreference='Stop'
if(-not $WritersStopped){throw 'Stop ingress, drain business tasks with the old workers/consumer, then stop all writers and pass -WritersStopped.'}
foreach($taskName in @('DATABASE_URL','DATABASE_USER','DATABASE_PASSWORD')) {
  if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($taskName,'Process'))){throw "$taskName required in this process."}
}
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskJava=Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/java25') -Directory | Where-Object {Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe')} | Select-Object -First 1
$taskDriver=Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/m2/com/mysql/mysql-connector-j') -Recurse -Filter '*.jar' | Select-Object -First 1
if(-not $taskJava -or -not $taskDriver){throw 'Prepare Java25, compile platform classes and download the MySQL driver first.'}
& (Join-Path $taskJava.FullName 'bin/java.exe') -cp ((Join-Path $taskRoot 'platform/target/classes')+';'+$taskDriver.FullName) dev.merchantpay.platform.outbox.EventTransportMaintenance --stopped-writers $Expected $Next
if($LASTEXITCODE -ne 0){throw 'Switch rejected. Keep old mode configured and inspect pending tasks/CDC lag/quarantine.'}
Write-Output "Persisted mode is $Next. Set EVENT_TRANSPORT=$Next for EVERY writer before restarting; retain volumes and connector offsets."
