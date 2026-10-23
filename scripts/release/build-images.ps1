[CmdletBinding()]
param([ValidatePattern('^[a-z0-9][a-z0-9_.-]{0,60}$')][string]$Tag='v1-local',[switch]$SkipBuild)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $taskRoot
try {
    New-Item -ItemType Directory -Path '.work/release' -Force | Out-Null
    if(-not $SkipBuild) {
        & pwsh -NoProfile -Command { & scripts/mvn.ps1 -MavenArguments @('package','-DskipTests','-B','-ntp') }
        if($LASTEXITCODE -ne 0){throw 'Backend packaging failed.'}
        & pwsh -NoProfile -File scripts/pnpm.ps1 -PnpmArguments build
        if($LASTEXITCODE -ne 0){throw 'Console build failed.'}
    }
    $taskImages=@()
    foreach($taskModule in @('platform','simulator','console')) {
        $taskImage='merchantpay/'+$taskModule+':'+$Tag
        & docker build --pull=false --network=none --label org.opencontainers.image.title=merchantpay --tag $taskImage $taskModule
        if($LASTEXITCODE -ne 0){throw ('Image build failed: '+$taskModule)}
        $taskInspect=(& docker image inspect $taskImage | ConvertFrom-Json)[0]
        if($LASTEXITCODE -ne 0 -or -not $taskInspect.Id){throw 'Image inspection failed.'}
        $taskImages+=@{module=$taskModule;image=$taskImage;id=$taskInspect.Id;created=$taskInspect.Created;user=$taskInspect.Config.User}
    }
    @{at=[DateTimeOffset]::UtcNow.ToString('o');tag=$Tag;images=$taskImages;runtimeVerified=$false} | ConvertTo-Json -Depth 6 | Set-Content '.work/release/images.json'
    Write-Output 'Images built. Runtime smoke and vulnerability gates remain separate.'
} finally {Pop-Location}
