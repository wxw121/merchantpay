[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$PnpmArguments)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskNode = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools\node24') -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'node.exe') } | Select-Object -First 1
if (-not $taskNode) { throw 'Run scripts/bootstrap.ps1 to prepare the project Node 24 toolchain.' }
$taskPreviousPath = $env:PATH
try {
    $env:PATH = $taskNode.FullName + ';' + $taskPreviousPath
    Push-Location (Join-Path $taskRoot 'console')
    try {
        $taskPnpmCli = Join-Path $taskRoot '.tools\pnpm\node_modules\pnpm\bin\pnpm.cjs'
        if (Test-Path -LiteralPath $taskPnpmCli) { & (Join-Path $taskNode.FullName 'node.exe') $taskPnpmCli @PnpmArguments }
        else { & pnpm.cmd @PnpmArguments }
        exit $LASTEXITCODE
    } finally { Pop-Location }
} finally { $env:PATH = $taskPreviousPath }
