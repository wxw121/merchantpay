$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
New-Item -ItemType Directory (Join-Path $taskRoot '.work') -Force|Out-Null
$taskHeldLock=$null
try {
    try {$taskHeldLock=[IO.File]::Open((Join-Path $taskRoot '.work/performance-baseline.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}catch [IO.IOException]{}
    function docker {throw 'Unexpected Docker operation before checking the active workload lock'}
    $taskRejected=$false
    try {& (Join-Path $PSScriptRoot 'start-containers.ps1') -Performance}catch{
        if($_.Exception.Message -notlike 'Container startup refused:*workspace lock*'){throw ('Startup must reject the workload lock before any Docker operation: '+$_.Exception.Message)}
        $taskRejected=$true
    }
    if(-not $taskRejected){throw 'Startup accepted an active workload lock'}
    'Container startup rejects an active workload/maintenance lock before Docker operations.'
} finally {if($taskHeldLock){$taskHeldLock.Dispose()}}
