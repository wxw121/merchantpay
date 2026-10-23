[CmdletBinding()]
param([ValidateSet('pay','query','dataset')][string]$Scenario='pay',[ValidateRange(1,10000)][int]$Rate=30,[ValidateRange(1,1000000)][int]$SecondsOrPayments=30,[ValidateRange(0,100)][int]$RefundPercent=0,[string]$Merchant='merchant-a',[switch]$Performance,[ValidateSet('full','after')][string]$ObserverMode='full')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $taskRoot 'scripts/LocalPerformance.ps1')
$taskOriginalAffinity=$null
if($Performance) {
    Assert-PerformanceCpu ([Environment]::ProcessorCount) ((Get-Process -Id $PID).ProcessorAffinity.ToInt64())
    $taskProfile=Get-Content (Join-Path $taskRoot '.work/local-profile.json') -Raw | ConvertFrom-Json
    if($taskProfile.mode -ne 'performance') { throw 'Start the project with -Performance before using the bounded generator.' }
}
. (Join-Path $taskRoot 'scripts\Import-LocalEnvironment.ps1')
$taskCredentials = Get-Content -LiteralPath (Join-Path $taskRoot '.data\platform\local-api-credentials.json') -Raw | ConvertFrom-Json
$taskCredential = $taskCredentials | Where-Object { $_.merchantId -eq $Merchant } | Select-Object -First 1
if (-not $taskCredential) { throw 'No local API credential for selected demo merchant. Use a fresh local profile or rotate and securely update the local credential file.' }
$env:LOAD_KEY_ID=$taskCredential.keyId
$env:LOAD_KEY_SECRET=$taskCredential.secret
$env:LOAD_MERCHANT=$Merchant
$taskJava=(Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools\java25') -Directory | Select-Object -First 1).FullName
$taskDriver=Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools\m2\com\mysql\mysql-connector-j') -Recurse -Filter '*.jar' | Select-Object -First 1
$taskClasses=Join-Path $taskRoot '.work/load-classes'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
Push-Location $taskRoot
try {
    & (Join-Path $taskJava 'bin/javac.exe') -d $taskClasses -cp $taskDriver.FullName (Join-Path $PSScriptRoot 'LocalLoad.java') (Join-Path $PSScriptRoot 'LoadMeasurements.java')
    if($LASTEXITCODE -ne 0) { throw 'Load generator compilation failed.' }
    $taskJavaOptions=@('-Xmx512m')
    if($Performance) {
        $taskProcess=Get-Process -Id $PID; $taskOriginalAffinity=$taskProcess.ProcessorAffinity
        $taskProcess.ProcessorAffinity=[IntPtr]192
        $taskJavaOptions+='-XX:ActiveProcessorCount=2'
    }
    & (Join-Path $taskJava 'bin\java.exe') @taskJavaOptions -cp ($taskClasses+[IO.Path]::PathSeparator+$taskDriver.FullName) LocalLoad $Scenario $Rate $SecondsOrPayments $RefundPercent $ObserverMode
    exit $LASTEXITCODE
}
finally { if($null -ne $taskOriginalAffinity){(Get-Process -Id $PID).ProcessorAffinity=$taskOriginalAffinity}; Pop-Location; Remove-Item Env:LOAD_KEY_ID,Env:LOAD_KEY_SECRET -ErrorAction SilentlyContinue }
