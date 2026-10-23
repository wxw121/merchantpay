[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArguments)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskJdk = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools\java25') -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } | Select-Object -First 1
if (-not $taskJdk) { throw 'Run scripts/bootstrap.ps1 to prepare the project Java 25 toolchain.' }
$taskPreviousJava = $env:JAVA_HOME
$taskPreviousPath = $env:PATH
try {
    $env:JAVA_HOME = $taskJdk.FullName
    $env:PATH = (Join-Path $taskJdk.FullName 'bin') + ';' + $taskPreviousPath
    Push-Location $taskRoot
    try {
        & mvn.cmd '-s' (Join-Path $taskRoot '.mvn\settings.xml') ('-Dmaven.repo.local=' + (Join-Path $taskRoot '.tools\m2')) @MavenArguments
        exit $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $taskPreviousJava
    $env:PATH = $taskPreviousPath
}
