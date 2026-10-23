[CmdletBinding()]
param([ValidateRange(1,600)][int]$Seconds=45)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $taskRoot 'scripts/Import-LocalEnvironment.ps1')
$taskJava=(Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/java25') -Directory | Select-Object -First 1).FullName
$taskDriver=(Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/m2/com/mysql/mysql-connector-j') -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$taskClasses=Join-Path $taskRoot '.work/diagnosis-classes'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
& (Join-Path $taskJava 'bin/javac.exe') -d $taskClasses -cp $taskDriver (Join-Path $PSScriptRoot 'LocalLoad.java') (Join-Path $PSScriptRoot 'LoadMeasurements.java') (Join-Path $PSScriptRoot 'LocalDiagnosis.java')
if($LASTEXITCODE -ne 0) { throw 'Diagnostic compilation failed.' }
Push-Location $taskRoot
try {
    & (Join-Path $taskJava 'bin/java.exe') -Xmx256m -cp ($taskClasses+[IO.Path]::PathSeparator+$taskDriver) LocalDiagnosis $Seconds
    exit $LASTEXITCODE
} finally { Pop-Location }
