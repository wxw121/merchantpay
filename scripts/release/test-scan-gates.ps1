$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
foreach($taskEntry in @('.github/workflows/verify.yml','scripts/verify.ps1')) {
    $taskText=[IO.File]::ReadAllText((Join-Path $taskRoot $taskEntry))
    if(-not $taskText.Contains('scan-images.ps1 -Manifest .work/release/configured-infrastructure-images.json')){throw ('Built infrastructure must enter the vulnerability gate: '+$taskEntry)}
}
Write-Output 'Both verification entries gate application and infrastructure scans.'
