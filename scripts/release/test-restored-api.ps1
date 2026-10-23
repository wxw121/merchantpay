$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskId='guard-'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$taskFolder=Join-Path $taskRoot ('.data/restores/'+$taskId)
$taskEvidence=Join-Path $taskRoot ('.work/release/restore-'+$taskId+'.json')
$taskApiEvidence=Join-Path $taskRoot ('.work/release/restore-'+$taskId+'-api.json')
$script:taskDockerCalls=0
function docker {$script:taskDockerCalls++;throw 'Guard regression must not touch Docker'}
try {
    New-Item -ItemType Directory $taskFolder|Out-Null
    New-Item -ItemType Directory (Split-Path -Parent $taskEvidence) -Force|Out-Null
    foreach($taskCase in @(@{source='container';s3=$false;verified=$true;existing=$false},@{source='native';s3=$true;verified=$true;existing=$false},@{source='native';s3=$false;verified=$false;existing=$false},@{source='native';s3=$false;verified=$true;existing=$true})) {
        @{source=$taskCase.source;includesS3=$taskCase.s3}|ConvertTo-Json|Set-Content (Join-Path $taskFolder 'manifest.json')
        @{complete=$taskCase.verified}|ConvertTo-Json|Set-Content $taskEvidence
        if($taskCase.existing){'{}'|Set-Content $taskApiEvidence}
        $taskRejected=$false
        try {& (Join-Path $PSScriptRoot 'verify-restored-api.ps1') -Id $taskId}catch{if($_.Exception.Message -notlike '*already verified native*' -and $_.Exception.Message -notlike '*immutable*'){throw};$taskRejected=$true}
        if(-not $taskRejected){throw 'An unsupported or already finalized application restore was accepted'}
    }
    if($script:taskDockerCalls -ne 0){throw 'A rejected application restore touched infrastructure'}
    Write-Output 'Application restore rejects unverified, container, S3 and finalized sets before touching infrastructure.'
} finally {
    # Exact invocation-owned files, then the now-empty test directory; never remove any existing restore tree.
    foreach($taskPath in @((Join-Path $taskFolder 'manifest.json'),$taskEvidence,$taskApiEvidence)){if(Test-Path -LiteralPath $taskPath){Remove-Item -LiteralPath $taskPath -Force}}
    if(Test-Path -LiteralPath $taskFolder){Remove-Item -LiteralPath $taskFolder}
}
