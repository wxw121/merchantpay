[CmdletBinding()]
param([switch]$SkipResolve)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    if(-not $SkipResolve) {
        & scripts/mvn.ps1 -MavenArguments @('-pl','platform,simulator','dependency:list','-DincludeScope=runtime','-DoutputFile=target/runtime-dependencies.txt','-B','-ntp')
        if($LASTEXITCODE -ne 0) { throw 'Runtime dependency resolution failed.' }
    }
    $taskPackages=@{}
    foreach($taskModule in @('platform','simulator')) {
        foreach($taskLine in Get-Content -LiteralPath ($taskModule+'/target/runtime-dependencies.txt')) {
            if($taskLine -match '^\s+([^:\s]+):([^:\s]+):jar:([^:\s]+):(compile|runtime)\s') {
                $taskCoordinate=$Matches[1]+':'+$Matches[2]+':'+$Matches[3]
                $taskPackages[$taskCoordinate]=@{package=@{ecosystem='Maven';name=($Matches[1]+':'+$Matches[2])};version=$Matches[3]}
            } elseif($taskLine -match '^\s+[^:\s]+:[^:\s]+:jar:') { throw ('Unparsed dependency coordinate: '+$taskLine.Trim()) }
        }
    }
    $taskCoordinates=@($taskPackages.Keys | Sort-Object)
    if($taskCoordinates.Count -eq 0) { throw 'No runtime dependencies parsed.' }
    $taskQueries=@($taskCoordinates | ForEach-Object { $taskPackages[$_] })
    # Only public library names and versions leave the machine; no code or secrets.
    New-Item -ItemType Directory -Path '.work' -Force | Out-Null
    @{queries=$taskQueries} | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8NoBOM '.work/osv-query.json'
    & curl.exe -4 --http1.1 --fail --silent --show-error --max-time 45 -H 'Content-Type: application/json' --data-binary '@.work/osv-query.json' --output '.work/osv-response.json' 'https://api.osv.dev/v1/querybatch'
    if($LASTEXITCODE -ne 0) { throw 'OSV network request failed; audit is not complete.' }
    $taskResponse=Get-Content -LiteralPath '.work/osv-response.json' -Raw | ConvertFrom-Json
    if($taskResponse.results.Count -ne $taskCoordinates.Count) { throw 'Incomplete OSV batch response.' }
    $taskFindings=@()
    for($taskIndex=0;$taskIndex -lt $taskCoordinates.Count;$taskIndex++) {
        $taskResult=$taskResponse.results[$taskIndex]
        if($taskResult.next_page_token) { throw 'OSV result requires pagination; audit is incomplete.' }
        foreach($taskVulnerability in $taskResult.vulns) { $taskFindings+=@{coordinate=$taskCoordinates[$taskIndex];id=$taskVulnerability.id;modified=$taskVulnerability.modified} }
    }
    $taskReport=@{source='OSV API querybatch';generatedAt=[DateTimeOffset]::UtcNow.ToString('o');coordinates=$taskCoordinates;checkedPackages=$taskCoordinates.Count;findings=$taskFindings;complete=$true}
    New-Item -ItemType Directory -Path '.work' -Force | Out-Null
    $taskReport | ConvertTo-Json -Depth 7 | Set-Content -Encoding utf8 '.work/backend-audit.json'
    Write-Output ('OSV runtime packages checked: '+$taskCoordinates.Count+'; findings: '+$taskFindings.Count)
    if($taskFindings.Count) { $taskFindings | ConvertTo-Json -Depth 4; throw 'Known dependency findings need review; see .work/backend-audit.json.' }
} finally { Pop-Location }
