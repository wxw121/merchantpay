# Shared path selection only; callers decide when to load the file or start processes.
function Resolve-LocalEnvironmentPath([string]$Root,[string]$EnvironmentFile) {
    if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
        $taskProfileFile=Join-Path $Root '.work/local-profile.json'
        if (Test-Path -LiteralPath $taskProfileFile -PathType Leaf) {
            try { $taskProfile=Get-Content -LiteralPath $taskProfileFile -Raw | ConvertFrom-Json -ErrorAction Stop }
            catch { throw 'Local profile is invalid. Pass an explicit -EnvironmentFile; no other environment will be substituted.' }
            if ($taskProfile.mode -eq 'native') {
                if ([string]::IsNullOrWhiteSpace($taskProfile.environmentFile)) { throw 'Native profile has no recorded environment file. Restart with the chosen file or pass -EnvironmentFile explicitly.' }
                $EnvironmentFile=$taskProfile.environmentFile
            } elseif ($taskProfile.mode -in @('normal','performance')) {
                $EnvironmentFile='.env'
            } else { throw 'Unknown local profile mode. Pass -EnvironmentFile explicitly.' }
        } elseif (Test-Path -LiteralPath (Join-Path $Root '.env.native') -PathType Leaf) {
            $EnvironmentFile='.env.native'
        } else { $EnvironmentFile='.env' }
    }
    $taskSelected=[IO.Path]::GetFullPath($EnvironmentFile,[IO.Path]::GetFullPath($Root))
    if (-not (Test-Path -LiteralPath $taskSelected -PathType Leaf)) { throw 'Selected environment file is missing. No fallback to a different environment was attempted.' }
    return $taskSelected
}
