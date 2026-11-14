[CmdletBinding()]
param([switch]$Docker)
$ErrorActionPreference='Stop'
if (-not $Docker) { throw 'This legacy drill uses Docker-backed infrastructure. No process was stopped. Native recovery acceptance remains separate; pass -Docker only when deliberately using that environment.' }
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskPreviousWorkers=$env:WORKERS_ENABLED
Push-Location $taskRoot
try {
    & scripts/stop-local.ps1
    $env:WORKERS_ENABLED='false'
    & scripts/start-local.ps1 -Docker -SkipBuild
    if($LASTEXITCODE -ne 0) { throw 'Worker-disabled startup failed.' }
    $taskPayment=& scripts/api-example.ps1 -BusinessNo ('restart-'+[Guid]::NewGuid().ToString('N'))
    if($taskPayment.status -ne 'PROCESSING') { throw 'The recovery fixture was not left pending.' }
    Write-Output ('Durably accepted '+$taskPayment.id+' while workers were disabled.')
    & scripts/stop-local.ps1
    $env:WORKERS_ENABLED='true'
    & scripts/start-local.ps1 -Docker -SkipBuild
    if($LASTEXITCODE -ne 0) { throw 'Recovery startup failed.' }
    . scripts/Import-LocalEnvironment.ps1 -EnvironmentFile '.env'
    $taskJava=(Get-ChildItem -LiteralPath '.tools/java25' -Directory | Select-Object -First 1).FullName
    $taskDriver=Get-ChildItem -LiteralPath '.tools/m2/com/mysql/mysql-connector-j' -Recurse -Filter '*.jar' | Select-Object -First 1
    & (Join-Path $taskJava 'bin/java.exe') -cp $taskDriver.FullName scripts/RecoveryCheck.java $taskPayment.id
    if($LASTEXITCODE -ne 0) { throw 'Recovery invariants failed.' }
} finally {
    # Always return the local demonstration to its normal worker-enabled state.
    $env:WORKERS_ENABLED='true'
    & scripts/start-local.ps1 -Docker -SkipBuild
    $env:WORKERS_ENABLED=$taskPreviousWorkers
    Pop-Location
}
