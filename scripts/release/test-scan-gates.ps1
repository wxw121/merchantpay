$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
foreach($taskEntry in @('.github/workflows/verify.yml','scripts/verify.ps1')) {
    $taskText=[IO.File]::ReadAllText((Join-Path $taskRoot $taskEntry))
    if(-not $taskText.Contains('scan-images.ps1 -Manifest .work/release/configured-infrastructure-images.json')){throw ('Built infrastructure must enter the vulnerability gate: '+$taskEntry)}
}
Write-Output 'Both verification entries gate application and infrastructure scans.'
. (Join-Path $PSScriptRoot 'scan-review.ps1')
$taskPolicy=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'scan-reviewed-exceptions.json') -Raw | ConvertFrom-Json
$taskNow=[datetimeoffset]::Parse('2026-11-12T00:00:00Z')
$taskFindings=@(foreach($taskRule in $taskPolicy.rules) {
    foreach($taskCve in $taskRule.cves) {
        [pscustomobject]@{Severity='HIGH';PkgPath=$taskRule.path;PkgName=$taskRule.package;InstalledVersion=$taskRule.version;VulnerabilityID=$taskCve}
    }
})
if($taskFindings.Count -ne 16){throw 'Reviewed finding count changed; review the exact CVE list.'}
$taskReport=[pscustomobject]@{Results=@([pscustomobject]@{Vulnerabilities=$taskFindings})}
$taskReview=Get-ScanReview -Module platform -Report $taskReport -Policy $taskPolicy -Now $taskNow
if($taskReview.reviewed.Count -ne 16 -or $taskReview.blocking.Count -ne 0 -or $taskReview.stale.Count -ne 0){throw 'Exact reviewed findings should match all rules.'}
if(-not (Test-ScanGate -Results @(@{complete=$true;unreviewedHighOrCritical=0;staleReviewRules=@()}) -ExpectedImages 1)){throw 'Exact reviewed scan should pass.'}
$taskChanged=[pscustomobject]@{Severity='HIGH';PkgPath=($taskFindings[0].PkgPath+'.changed');PkgName=$taskFindings[0].PkgName;InstalledVersion=$taskFindings[0].InstalledVersion;VulnerabilityID=$taskFindings[0].VulnerabilityID}
$taskChangedVersion=[pscustomobject]@{Severity='HIGH';PkgPath=$taskFindings[0].PkgPath;PkgName=$taskFindings[0].PkgName;InstalledVersion='0.0.0';VulnerabilityID=$taskFindings[0].VulnerabilityID}
$taskChangedPackage=[pscustomobject]@{Severity='HIGH';PkgPath=$taskFindings[0].PkgPath;PkgName='example:unreviewed';InstalledVersion=$taskFindings[0].InstalledVersion;VulnerabilityID=$taskFindings[0].VulnerabilityID}
$taskChangedCase=[pscustomobject]@{Severity='HIGH';PkgPath=$taskFindings[0].PkgPath.ToUpperInvariant();PkgName=$taskFindings[0].PkgName;InstalledVersion=$taskFindings[0].InstalledVersion;VulnerabilityID=$taskFindings[0].VulnerabilityID}
$taskNew=[pscustomobject]@{Severity='HIGH';PkgPath=$taskFindings[0].PkgPath;PkgName=$taskFindings[0].PkgName;InstalledVersion=$taskFindings[0].InstalledVersion;VulnerabilityID='CVE-2099-00001'}
$taskReport.Results[0].Vulnerabilities=@($taskFindings)+@($taskChanged,$taskChangedVersion,$taskChangedPackage,$taskChangedCase,$taskNew)
$taskReview=Get-ScanReview -Module platform -Report $taskReport -Policy $taskPolicy -Now $taskNow
if($taskReview.reviewed.Count -ne 16 -or $taskReview.blocking.Count -ne 5){throw 'Changed path, package, version, case and new CVE must block.'}
if(Test-ScanGate -Results @(@{complete=$true;unreviewedHighOrCritical=$taskReview.blocking.Count;staleReviewRules=@()}) -ExpectedImages 1){throw 'New findings must fail the gate.'}
$taskReport.Results[0].Vulnerabilities=@($taskFindings | Select-Object -Skip 1)
$taskReview=Get-ScanReview -Module platform -Report $taskReport -Policy $taskPolicy -Now $taskNow
if($taskReview.stale.Count -ne 1){throw 'Missing reviewed finding must expose a stale rule.'}
if(Test-ScanGate -Results @(@{complete=$true;unreviewedHighOrCritical=0;staleReviewRules=$taskReview.stale}) -ExpectedImages 1){throw 'Stale rules must fail the gate.'}
$taskReport.Results[0].Vulnerabilities=$taskFindings
$taskReview=Get-ScanReview -Module platform -Report $taskReport -Policy $taskPolicy -Now ([datetimeoffset]::Parse($taskPolicy.expiresAt))
if($taskReview.blocking.Count -ne 16 -or $taskReview.stale.Count -ne 16){throw 'Expired review must block all findings.'}
$taskReview=Get-ScanReview -Module mysql -Report $taskReport -Policy $taskPolicy -Now $taskNow
if($taskReview.reviewed.Count -ne 0 -or $taskReview.blocking.Count -ne 16 -or $taskReview.stale.Count -ne 0){throw 'Review cannot spill into infrastructure images.'}
if(Test-ScanGate -Results @(@{complete=$false;unreviewedHighOrCritical=0;staleReviewRules=@()}) -ExpectedImages 1){throw 'Incomplete scan must fail the gate.'}
if(Test-ScanGate -Results @(@{complete=$true;staleReviewRules=@()}) -ExpectedImages 1){throw 'Missing unreviewed count must fail the gate.'}
Write-Output 'Exact reviewed findings, new findings, expiry, stale rules and incomplete scans are gated.'
