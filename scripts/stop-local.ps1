[CmdletBinding()]
param([switch]$Infrastructure)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskState = Join-Path $taskRoot '.work\local-processes.json'
if (Test-Path -LiteralPath $taskState) {
    foreach ($taskRecord in (Get-Content -LiteralPath $taskState -Raw | ConvertFrom-Json)) {
        $taskProcess = Get-Process -Id $taskRecord.pid -ErrorAction SilentlyContinue
        if ($taskProcess -and $taskProcess.StartTime.ToUniversalTime().Ticks -eq $taskRecord.startedTicks -and $taskProcess.Path -eq $taskRecord.executable) {
            Stop-Process -Id $taskProcess.Id
            Write-Output ($taskRecord.name + ' stopped.')
        }
    }
}
if ($Infrastructure) {
    Push-Location $taskRoot
    try { & docker.exe compose --env-file .env -f infra/compose.yaml stop } finally { Pop-Location }
}
