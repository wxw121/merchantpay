$taskEnvironmentRoot = Split-Path -Parent $PSScriptRoot
$taskEnvironmentPath = Join-Path $taskEnvironmentRoot '.env'
if (-not (Test-Path -LiteralPath $taskEnvironmentPath)) { throw 'Run scripts/init-local.ps1 first.' }
foreach ($taskEnvironmentLine in [IO.File]::ReadAllLines($taskEnvironmentPath)) {
    if ($taskEnvironmentLine -match '^([A-Z_]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process') }
}
