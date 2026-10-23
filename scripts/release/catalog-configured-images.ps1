$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $taskRoot
try {
    $taskReferences=[Collections.Generic.HashSet[string]]::new()
    foreach($taskFile in @('infra/compose.yaml','infra/compose.storage.yaml')) {
        foreach($taskLine in Get-Content $taskFile) {
            if($taskLine -match '^\s+image:\s*(.+?)\s*(?:#.*)?$') {
                $taskReference=$Matches[1].Trim().Trim('"',"'")
                if($taskReference -notmatch '^[a-zA-Z0-9./:_@-]+$'){throw 'Configured image reference must be explicit; unresolved variables cannot be skipped by the scan'}
                [void]$taskReferences.Add($taskReference)
            }
        }
    }
    if($taskReferences.Count -lt 4){throw 'Configured infrastructure image inventory is incomplete'}
    $taskImages=@();$taskModules=[Collections.Generic.HashSet[string]]::new()
    foreach($taskReference in ($taskReferences|Sort-Object)) {
        $taskModule=$taskReference.Split('/')[-1].Split(':')[0].Split('@')[0]
        if(-not $taskModules.Add($taskModule)){throw 'Duplicate infrastructure module filenames would overwrite scan evidence'}
        $taskId=& docker image inspect --format '{{.Id}}' $taskReference 2>$null
        if($LASTEXITCODE -ne 0) {
            & docker pull $taskReference
            if($LASTEXITCODE -ne 0){throw 'Configured infrastructure image unavailable; no clean scan may be claimed'}
            $taskId=& docker image inspect --format '{{.Id}}' $taskReference
            if($LASTEXITCODE -ne 0){throw 'Configured image identity unavailable'}
        }
        $taskImages+=@{module=$taskModule;image=$taskReference;id=$taskId}
    }
    New-Item -ItemType Directory .work/release -Force|Out-Null
    @{at=[DateTimeOffset]::UtcNow;source=@('infra/compose.yaml','infra/compose.storage.yaml');images=$taskImages}|ConvertTo-Json -Depth 6|Set-Content .work/release/configured-infrastructure-images.json
} finally {Pop-Location}
