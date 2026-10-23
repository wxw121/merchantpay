$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $taskRoot
$taskLock=$null
try {
    New-Item -ItemType Directory .work/release -Force|Out-Null
    $taskDeadline=[DateTime]::UtcNow.AddSeconds(30)
    while(-not $taskLock) {
        try {$taskLock=[IO.File]::Open((Join-Path $taskRoot '.work/infrastructure-build.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}catch [IO.IOException]{if([DateTime]::UtcNow -gt $taskDeadline){throw};Start-Sleep -Milliseconds 200}
    }
    $taskPrevious=if(Test-Path .work/release/infrastructure-images.json){Get-Content .work/release/infrastructure-images.json -Raw|ConvertFrom-Json}else{$null}
    $taskImages=@()
    foreach($taskModule in @('mysql','storage','keycloak')) {
        $taskImage='merchantpay/'+$taskModule+':v1-local'
        $taskSource=(Get-FileHash ('infra/images/'+$taskModule+'.Dockerfile')).Hash
        $taskExisting=& docker image inspect --format '{{.Id}}' $taskImage 2>$null
        $taskSaved=@($taskPrevious.images|Where-Object module -eq $taskModule)
        if($LASTEXITCODE -ne 0 -or $taskSaved.Count -ne 1 -or $taskSaved[0].id -ne $taskExisting -or $taskSaved[0].dockerfileSha256 -ne $taskSource) {
            & docker build --provenance=false --sbom=false -f ('infra/images/'+$taskModule+'.Dockerfile') -t $taskImage infra/images
            if($LASTEXITCODE -ne 0){throw ('Infrastructure image build failed: '+$taskModule)}
        }
        $taskId=& docker image inspect --format '{{.Id}}' $taskImage
        if($LASTEXITCODE -ne 0){throw 'Infrastructure identity unavailable'}
        $taskImages+=@{module=$taskModule;image=$taskImage;id=$taskId;dockerfileSha256=$taskSource}
    }
    New-Item -ItemType Directory .work/release -Force|Out-Null
    @{at=[DateTimeOffset]::UtcNow.ToString('o');images=$taskImages}|ConvertTo-Json -Depth 5|Set-Content .work/release/infrastructure-images.json
} finally {if($taskLock){$taskLock.Dispose()};Pop-Location}
