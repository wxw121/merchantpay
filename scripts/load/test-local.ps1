[CmdletBinding()]
param([switch]$MySql)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskJava=(Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/java25') -Directory | Select-Object -First 1).FullName
$taskDriver=(Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/m2/com/mysql/mysql-connector-j') -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$taskClasses=Join-Path $taskRoot '.work/load-tests'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
$taskSources=@(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $taskJava 'bin/javac.exe') -d $taskClasses -cp $taskDriver @taskSources
if($LASTEXITCODE -ne 0) { throw 'Load test compilation failed.' }
if($MySql) { . (Join-Path $taskRoot 'scripts/Import-LocalEnvironment.ps1') }
if($MySql) {
    & (Join-Path $taskJava 'bin/java.exe') -Xmx512m -cp ($taskClasses+[IO.Path]::PathSeparator+$taskDriver) LoadMeasurementsTest --mysql
} else {
    & (Join-Path $taskJava 'bin/java.exe') -Xmx512m -cp ($taskClasses+[IO.Path]::PathSeparator+$taskDriver) LoadMeasurementsTest
}
if($LASTEXITCODE -ne 0) { throw 'Load measurement tests failed.' }
& (Join-Path $taskJava 'bin/java.exe') -Xmx256m -cp ($taskClasses+[IO.Path]::PathSeparator+$taskDriver) DiagnosisMeasurementsTest
if($LASTEXITCODE -ne 0) { throw 'Diagnostic measurement tests failed.' }
& (Join-Path $taskJava 'bin/java.exe') -Xmx256m -cp ($taskClasses+[IO.Path]::PathSeparator+$taskDriver) CommitWaitSamplerTest
if($LASTEXITCODE -ne 0) { throw 'Commit wait classification tests failed.' }
& (Join-Path $taskJava 'bin/java.exe') -Xmx256m -cp ($taskClasses+[IO.Path]::PathSeparator+$taskDriver) AcceptanceToolsTest
if($LASTEXITCODE -ne 0) { throw 'Acceptance protocol tests failed.' }
& (Join-Path $taskJava 'bin/javac.exe') -d $taskClasses (Join-Path $taskRoot 'simulator/src/main/java/dev/merchantpay/simulator/StandardProfile.java')
if($LASTEXITCODE -ne 0) { throw 'Standard channel profile compilation failed.' }
& (Join-Path $taskJava 'bin/java.exe') -Xmx256m -cp $taskClasses StandardProfileTest
if($LASTEXITCODE -ne 0) { throw 'Standard channel profile tests failed.' }
