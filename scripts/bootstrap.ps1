[CmdletBinding()]
param([switch]$UseRanges)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskTools = Join-Path $taskRoot '.tools'
$taskDownloads = Join-Path $taskTools 'downloads'
New-Item -ItemType Directory -Path $taskDownloads -Force | Out-Null
$taskLock = Get-Content -LiteralPath (Join-Path $taskRoot 'toolchain.lock.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($taskTool in $taskLock) {
    $taskDestination = [System.IO.Path]::GetFullPath((Join-Path $taskTools $taskTool.name))
    if (-not $taskDestination.StartsWith($taskTools + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid toolchain destination.' }
    if (Test-Path -LiteralPath $taskDestination) { continue }
    if ($UseRanges) {
        if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Range downloads require PowerShell 7.' }
        & (Join-Path $PSScriptRoot 'download-ranged.ps1') -Url $taskTool.url -Length $taskTool.length -Sha256 $taskTool.sha256 -Name $taskTool.name
        continue
    }
    $taskArchive = Join-Path $taskDownloads $taskTool.archive
    if (-not (Test-Path -LiteralPath $taskArchive)) {
        Invoke-WebRequest -Uri $taskTool.url -OutFile $taskArchive -TimeoutSec 300
    }
    $taskHash = (Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash
    if ($taskHash -ne $taskTool.sha256) { throw ('Toolchain checksum mismatch: ' + $taskTool.name) }
    Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskDestination
    Write-Host ('Prepared project toolchain: ' + $taskTool.name)
}
$taskNode = Get-ChildItem -LiteralPath (Join-Path $taskTools 'node24') -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'node.exe') } | Select-Object -First 1
$taskPnpmCli = Join-Path $taskTools 'pnpm\node_modules\pnpm\bin\pnpm.cjs'
if (-not (Test-Path -LiteralPath $taskPnpmCli)) {
    $taskConsole = Get-Content -LiteralPath (Join-Path $taskRoot 'console\package.json') -Raw | ConvertFrom-Json
    if ($taskConsole.packageManager -notmatch '^pnpm@([0-9]+\.[0-9]+\.[0-9]+)$') { throw 'Invalid pinned package manager.' }
    $taskPnpmVersion = $Matches[1]
    & (Join-Path $taskNode.FullName 'npm.cmd') install --prefix (Join-Path $taskTools 'pnpm') --cache (Join-Path $taskTools 'npm-cache') --no-audit --no-fund ('pnpm@'+$taskPnpmVersion)
    if ($LASTEXITCODE -ne 0) { throw 'Project-local pnpm installation failed.' }
}
Write-Host 'Toolchains are project-local. System PATH and JAVA_HOME were not changed.'
