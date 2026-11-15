[CmdletBinding()]
param([switch]$WritersStopped)
$ErrorActionPreference = 'Stop'
if (-not $WritersStopped) {
    throw 'Stop every old platform writer and pass -WritersStopped before repairing event identities.'
}
foreach ($taskName in @('DATABASE_URL', 'DATABASE_USER', 'DATABASE_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($taskName, 'Process'))) {
        throw "$taskName must be set in this process."
    }
}
$taskRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskClass = Join-Path $taskRoot 'platform/target/classes/dev/merchantpay/platform/outbox/OutboxEventRegistryMaintenance.class'
if (-not (Test-Path -LiteralPath $taskClass)) {
    throw 'Build the new platform classes first with scripts/mvn.ps1 -MavenArguments @(''-o'',''-pl'',''platform'',''compile'').'
}
$taskJdk = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/java25') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } |
    Select-Object -First 1
$taskDriver = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/m2/com/mysql/mysql-connector-j') -Recurse -Filter '*.jar' |
    Select-Object -First 1
if (-not $taskJdk -or -not $taskDriver) { throw 'The prepared Java 25 and MySQL driver are required.' }
& (Join-Path $taskJdk.FullName 'bin/java.exe') -cp ((Join-Path $taskRoot 'platform/target/classes') + ';' + $taskDriver.FullName) dev.merchantpay.platform.outbox.OutboxEventRegistryMaintenance --stopped-writers
if ($LASTEXITCODE -ne 0) { throw 'Outbox identity reconciliation failed; keep writers stopped.' }
