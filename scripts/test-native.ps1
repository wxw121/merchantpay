[CmdletBinding()]
param([switch]$Execute)
$ErrorActionPreference='Stop'
if (-not $Execute) { throw 'No tests started. Use scripts/check-native.ps1 for static checks; -Execute explicitly runs the bounded H2/unit selection.' }
$taskPreviousOptions=$env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS='-Xmx384m -XX:ActiveProcessorCount=1 -XX:TieredStopAtLevel=1'
    # Fixed reviewed selection: no MySql*, S3, Kafka container, browser or service tests.
    $taskTests='V2RecoveryPolicyTest,RecoveryWindowMigrationTest,EventDeliveryTest,TaskHistoryTest,DurableTaskTest,TaskWorkerConcurrencyTest,TaskWorkerMetricsTest'
    & (Join-Path $PSScriptRoot 'mvn.ps1') -MavenArguments @('-o','-pl','platform','test',('-Dtest='+$taskTests),'-DforkCount=1','-DreuseForks=true','-DargLine=-Dfile.encoding=UTF-8 -Xmx384m -XX:ActiveProcessorCount=1 -XX:TieredStopAtLevel=1','-B','-ntp')
    if ($LASTEXITCODE -ne 0) { throw 'Native H2/unit tests failed. This selection does not certify MySQL concurrency, external contracts or R2.' }
    & (Join-Path $PSScriptRoot 'test-local-environment.ps1') -Execute
} finally {
    $env:JAVA_TOOL_OPTIONS=$taskPreviousOptions
}
