[CmdletBinding()]
param([switch]$Execute)
$ErrorActionPreference='Stop'
if (-not $Execute) { throw 'No checks executed. Add -Execute to run isolated file-only environment selection checks.' }
$taskRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$taskFixture=Join-Path $taskRoot ('.work/environment-selection-'+[Guid]::NewGuid().ToString('N'))
$taskProfile=Join-Path $taskFixture '.work/local-profile.json'
$taskLegacy=Join-Path $taskFixture '.env'
$taskNative=Join-Path $taskFixture '.env.native'
$taskCustom=Join-Path $taskFixture 'chosen.env'
function Assert-Selected([string]$Expected,[string]$Explicit='') {
    $taskActual=Resolve-LocalEnvironmentPath -Root $taskFixture -EnvironmentFile $Explicit
    if ($taskActual -ne $Expected) { throw 'The selected environment did not match the requested profile.' }
}
function Assert-SelectionFails {
    $taskFailed=$false
    try { Resolve-LocalEnvironmentPath -Root $taskFixture | Out-Null } catch { $taskFailed=$true }
    if (-not $taskFailed) { throw 'Ambiguous or missing native configuration silently fell back to another environment.' }
}
try {
    New-Item -ItemType Directory -Path (Split-Path -Parent $taskProfile) -Force | Out-Null
    [IO.File]::WriteAllText($taskLegacy,'DATABASE_URL=legacy')
    Assert-Selected $taskLegacy
    [IO.File]::WriteAllText($taskNative,'DATABASE_URL=native')
    [IO.File]::WriteAllText($taskCustom,'DATABASE_URL=chosen')
    Assert-Selected $taskNative
    [pscustomobject]@{mode='native';environmentFile=$taskCustom} | ConvertTo-Json | Set-Content -LiteralPath $taskProfile
    Assert-Selected $taskCustom
    Assert-Selected $taskLegacy '.env'
    [pscustomobject]@{mode='normal'} | ConvertTo-Json | Set-Content -LiteralPath $taskProfile
    Assert-Selected $taskLegacy
    [pscustomobject]@{mode='performance'} | ConvertTo-Json | Set-Content -LiteralPath $taskProfile
    Assert-Selected $taskLegacy
    [pscustomobject]@{mode='native';environmentFile=(Join-Path $taskFixture 'missing.env')} | ConvertTo-Json | Set-Content -LiteralPath $taskProfile
    Assert-SelectionFails
    [pscustomobject]@{mode='native'} | ConvertTo-Json | Set-Content -LiteralPath $taskProfile
    Assert-SelectionFails
    [IO.File]::WriteAllText($taskProfile,'not-json')
    Assert-SelectionFails
    Assert-Selected $taskCustom 'chosen.env'
    Write-Output 'Environment selection checks passed. No credentials loaded, processes started or network requests made.'
} finally {
    foreach ($taskFile in @($taskProfile,$taskLegacy,$taskNative,$taskCustom)) { if (Test-Path -LiteralPath $taskFile) { [IO.File]::Delete($taskFile) } }
    if (Test-Path -LiteralPath (Split-Path -Parent $taskProfile)) { [IO.Directory]::Delete((Split-Path -Parent $taskProfile)) }
    if (Test-Path -LiteralPath $taskFixture) { [IO.Directory]::Delete($taskFixture) }
}
