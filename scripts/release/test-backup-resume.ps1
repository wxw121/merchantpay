$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'BackupResume.ps1')
$taskTestRoot=Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.work/backup-resume-test'
New-Item -ItemType Directory (Join-Path $taskTestRoot '.work') -Force|Out-Null
$script:taskResumeScenario='ok';$script:taskNativeCalls=0;$script:taskCheckedUrls=[Collections.Generic.List[string]]::new()
function docker {$global:LASTEXITCODE=if($script:taskResumeScenario -eq 'docker-failed'){1}else{0};if($args[0] -eq 'exec'){$script:taskObjectReferences}}
function Invoke-RestMethod {param($Uri);$script:taskCheckedUrls.Add([string]$Uri);if($script:taskResumeScenario -eq 'identity-failed'){throw 'offline'};@{status=if($script:taskResumeScenario -eq 'health-failed'){'DOWN'}else{'UP'}}}
function Invoke-WebRequest {param($Uri);$script:taskCheckedUrls.Add([string]$Uri);@{StatusCode=200}}
function Start-Sleep {}
function Start-BackupNativeSource {$script:taskNativeCalls++;if($script:taskResumeScenario -eq 'native-failed'){throw 'Native startup failed'}}
foreach($taskScenario in @('docker-failed','identity-failed','native-failed','health-failed')) {
    $script:taskResumeScenario=$taskScenario;$taskRejected=$false
    try {Resume-LocalBackupSource -Source native -Containers @('owned-keycloak') -NativeStopped $true -NativeMode performance -Root $taskTestRoot}catch{$taskRejected=$true}
    if(-not $taskRejected){throw ('Source resume incorrectly succeeded: '+$taskScenario)}
}
$script:taskResumeScenario='ok'
Resume-LocalBackupSource -Source native -Containers @('owned-keycloak') -NativeStopped $true -NativeMode performance -Root $taskTestRoot
if($script:taskNativeCalls -lt 3){throw 'Native startup was not exercised'}
Resume-LocalBackupSource -Source container -Containers @('merchantpay-container-v1-platform-1','merchantpay-container-v1-simulator-1','merchantpay-container-v1-console-1','merchantpay-container-v1-keycloak-1') -NativeStopped $false -NativeMode normal -Root $taskTestRoot
foreach($taskUrl in @('http://127.0.0.1:5173/','http://127.0.0.1:15173/','http://127.0.0.1:9091/actuator/health','http://127.0.0.1:19091/actuator/health')){if(-not $script:taskCheckedUrls.Contains($taskUrl)){throw ('Missing source health check: '+$taskUrl)}}
$script:taskObjectReferences='0';Assert-NativeBackupLocalObjects 'merchantpay-v1'
$script:taskObjectReferences='1';$taskRejected=$false
try {Assert-NativeBackupLocalObjects 'merchantpay-v1'}catch{if($_.Exception.Message -notlike '*S3 references*'){throw};$taskRejected=$true}
if(-not $taskRejected){throw 'An S3 object published between live and stopped checks must reject the local-only backup'}
Write-Output 'Backup source resume rejects Docker, identity, native startup and health failures; healthy resume passes.'
