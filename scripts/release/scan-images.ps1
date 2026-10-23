[CmdletBinding()]
param([string]$Manifest='.work/release/images.json')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $taskRoot
try {
    $taskManifest=Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
    $taskFolder=Join-Path $taskRoot ('.work/release/security-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
    New-Item -ItemType Directory -Path $taskFolder -Force | Out-Null
    $taskResults=@()
    foreach($taskImage in $taskManifest.images) {
        $taskCurrent=(& docker image inspect --format '{{.Id}}' $taskImage.image)
        if($LASTEXITCODE -ne 0 -or $taskCurrent -ne $taskImage.id){throw 'Image identity changed before scan.'}
        $taskOutput=Join-Path $taskFolder ($taskImage.module+'.json')
        & trivy image --image-src docker --scanners vuln --no-progress --db-repository public.ecr.aws/aquasecurity/trivy-db:2 --java-db-repository public.ecr.aws/aquasecurity/trivy-java-db:1 --cache-dir (Join-Path $taskRoot '.work/trivy-cache') --timeout 10m --format json --output $taskOutput $taskImage.image
        $taskExit=$LASTEXITCODE
        if($taskExit -ne 0 -or -not (Test-Path $taskOutput)) {
            $taskResults+=@{module=$taskImage.module;id=$taskImage.id;complete=$false;exitCode=$taskExit}
            continue
        }
        $taskReport=Get-Content -LiteralPath $taskOutput -Raw | ConvertFrom-Json
        $taskPackages=@($taskReport.Results | Where-Object { $_.Packages -or $_.Vulnerabilities })
        $taskVulns=@($taskReport.Results | ForEach-Object Vulnerabilities | Where-Object { $null -ne $_ })
        $taskBlocking=@($taskVulns | Where-Object {$_.Severity -in @('HIGH','CRITICAL')})
        $taskResults+=@{module=$taskImage.module;id=$taskImage.id;complete=($taskReport.SchemaVersion -gt 0 -and $taskReport.Results.Count -gt 0);findings=$taskVulns.Count;highOrCritical=$taskBlocking.Count;results=$taskReport.Results.Count;os=$taskReport.Metadata.OS;report=$taskOutput;sha256=(Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256).Hash}
    }
    $taskPassed=($taskResults.Count -eq $taskManifest.images.Count -and $taskResults.Count -gt 0 -and @($taskResults | Where-Object {$_.complete -ne $true -or $_.highOrCritical -gt 0}).Count -eq 0)
    @{at=[DateTimeOffset]::UtcNow.ToString('o');scanner=((& trivy --version) -join "`n");images=$taskResults;imageVulnerabilityGatePassed=$taskPassed;jdkVendorReviewRequired=$true;hostWindowsOsCovered=$false} | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $taskFolder 'summary.json')
    Write-Output ('Security reports: '+$taskFolder)
    if(-not $taskPassed){throw 'Image vulnerability gate did not pass. Missing scans are not clean scans.'}
} finally {Pop-Location}
