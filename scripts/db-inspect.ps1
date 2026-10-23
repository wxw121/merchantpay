[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
$taskJava = (Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools\java25') -Directory | Select-Object -First 1).FullName
$taskDriver = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools\m2\com\mysql\mysql-connector-j') -Recurse -Filter '*.jar' | Select-Object -First 1
& (Join-Path $taskJava 'bin\java.exe') -cp $taskDriver.FullName (Join-Path $PSScriptRoot 'DbInspect.java')
exit $LASTEXITCODE
