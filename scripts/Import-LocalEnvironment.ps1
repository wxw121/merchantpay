param([string]$EnvironmentFile)
$taskEnvironmentRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$taskEnvironmentPath=Resolve-LocalEnvironmentPath -Root $taskEnvironmentRoot -EnvironmentFile $EnvironmentFile
foreach ($taskEnvironmentLine in [IO.File]::ReadAllLines($taskEnvironmentPath)) {
    if ($taskEnvironmentLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process') }
}
