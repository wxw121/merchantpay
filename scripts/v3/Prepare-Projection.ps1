[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_-]{1,36}$')][string]$Generation,
    [Parameter(Mandatory)][ValidateLength(1, 1000)][string]$Reason,
    [switch]$WritersStopped
)
$ErrorActionPreference = 'Stop'
if (-not $WritersStopped -or [string]::IsNullOrWhiteSpace($Reason)) {
    throw 'Stop all business writers, event consumers and projection workers, then pass -WritersStopped and a reason.'
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
$taskClass = Join-Path $taskRoot 'platform/target/classes/dev/merchantpay/platform/sharding/ShardProjectionGenerationMaintenance.class'
if (-not (Test-Path -LiteralPath $taskClass)) { throw 'Compile the new platform classes first.' }
$taskJava = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/java25') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } |
    Select-Object -First 1
$taskDriver = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/m2/com/mysql/mysql-connector-j') -Recurse -Filter '*.jar' |
    Select-Object -First 1
if (-not $taskJava -or -not $taskDriver) { throw 'Prepared Java 25 and the MySQL driver are required.' }
& (Join-Path $taskJava.FullName 'bin/java.exe') -cp ((Join-Path $taskRoot 'platform/target/classes') + ';' + $taskDriver.FullName) dev.merchantpay.platform.sharding.ShardProjectionGenerationMaintenance --stopped-writers $Generation $Reason
if ($LASTEXITCODE -ne 0) {
    throw 'Projection preparation failed or may be partial. Keep writers stopped, inspect all four projection controls, then retry with the same generation.'
}
Write-Output "Four sources have BUILDING generation $Generation. Verify controls and connector offsets before starting the sharded projection consumer and worker."
