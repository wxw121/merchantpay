[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('POLLING', 'CDC')][string]$Expected,
    [Parameter(Mandatory)][ValidateSet('POLLING', 'CDC')][string]$Next,
    [switch]$WritersStopped
)
$ErrorActionPreference = 'Stop'
if (-not $WritersStopped -or $Expected -eq $Next) {
    throw 'Stop ingress, drain all four sources, stop every writer and consumer, then select different modes and pass -WritersStopped.'
}
foreach ($taskName in @(
    'DATABASE_URL', 'DATABASE_USER', 'DATABASE_PASSWORD',
    'SHARD_1_DATABASE_URL', 'SHARD_1_DATABASE_USER', 'SHARD_1_DATABASE_PASSWORD',
    'SHARD_2_DATABASE_URL', 'SHARD_2_DATABASE_USER', 'SHARD_2_DATABASE_PASSWORD',
    'SHARD_3_DATABASE_URL', 'SHARD_3_DATABASE_USER', 'SHARD_3_DATABASE_PASSWORD'
)) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($taskName, 'Process'))) {
        throw "$taskName required in this process."
    }
}
$taskRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskClass = Join-Path $taskRoot 'platform/target/classes/dev/merchantpay/platform/sharding/ShardEventTransportMaintenance.class'
if (-not (Test-Path -LiteralPath $taskClass)) { throw 'Compile the new platform classes first.' }
$taskJava = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/java25') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } |
    Select-Object -First 1
$taskDriver = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/m2/com/mysql/mysql-connector-j') -Recurse -Filter '*.jar' |
    Select-Object -First 1
if (-not $taskJava -or -not $taskDriver) { throw 'Prepared Java 25 and the MySQL driver are required.' }
& (Join-Path $taskJava.FullName 'bin/java.exe') -cp ((Join-Path $taskRoot 'platform/target/classes') + ';' + $taskDriver.FullName) dev.merchantpay.platform.sharding.ShardEventTransportMaintenance --stopped-writers $Expected $Next
if ($LASTEXITCODE -ne 0) {
    throw 'Switch failed or has an uncertain partial commit. Keep all writers stopped, inspect all four source modes and cutoffs, then retry with the same Expected and Next.'
}
Write-Output "All four persisted modes are $Next. Keep writers stopped until connector offsets and source cutoffs are independently checked; then set EVENT_TRANSPORT=$Next on every writer."
