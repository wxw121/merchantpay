function Get-ScanReview {
    param([string]$Module,[object]$Report,[object]$Policy,[datetimeoffset]$Now=[datetimeoffset]::UtcNow)
    $expires=[datetimeoffset]::Parse($Policy.expiresAt)
    $rules=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach($rule in @($Policy.rules | Where-Object {$_.module -eq $Module})) {
        if(-not $rule.path -or -not $rule.package -or -not $rule.version -or -not $rule.reason -or @($rule.cves | Where-Object {$_}).Count -eq 0){throw 'Incomplete scan review rule.'}
        foreach($cve in $rule.cves) {
            if($cve -notmatch '^CVE-[0-9]{4}-[0-9]{4,}$'){throw 'Invalid reviewed vulnerability ID.'}
            $key=($Module,$rule.path,$rule.package,$rule.version,$cve) -join '|'
            if($rules.ContainsKey($key)){throw 'Duplicate scan review rule.'}
            $rules[$key]=$rule
        }
    }
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $reviewed=@()
    $blocking=@()
    foreach($result in $Report.Results) {
        foreach($finding in @($result.Vulnerabilities | Where-Object {$_.Severity -in @('HIGH','CRITICAL')})) {
            # Trivy uses PkgPath for nested JARs and Result.Target for compiled binaries.
            $location=if($finding.PkgPath){$finding.PkgPath}else{$result.Target}
            $key=($Module,$location,$finding.PkgName,$finding.InstalledVersion,$finding.VulnerabilityID) -join '|'
            if($rules.ContainsKey($key) -and $Now -lt $expires) {
                [void]$seen.Add($key)
                $reviewed+=,$finding
            } else {
                $blocking+=,$finding
            }
        }
    }
    $stale=@($rules.Keys | Where-Object {-not $seen.Contains($_)})
    return @{reviewed=$reviewed;blocking=$blocking;stale=$stale;expiresAt=$Policy.expiresAt}
}

function Test-ScanGate {
    param([object[]]$Results,[int]$ExpectedImages)
    return ($Results.Count -eq $ExpectedImages -and $Results.Count -gt 0 -and
        @($Results | Where-Object {$_.complete -ne $true -or $_.unreviewedHighOrCritical -ne 0 -or @($_.staleReviewRules).Count -gt 0}).Count -eq 0)
}
