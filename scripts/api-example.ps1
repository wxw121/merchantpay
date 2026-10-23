[CmdletBinding()]
param([string]$Merchant='merchant-a',[string]$BusinessNo=('example-'+[Guid]::NewGuid().ToString('N')))
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskKeys=Get-Content -LiteralPath (Join-Path $taskRoot '.data/platform/local-api-credentials.json') -Raw | ConvertFrom-Json
$taskKey=$taskKeys | Where-Object { $_.merchantId -eq $Merchant } | Select-Object -First 1
if(-not $taskKey) { throw 'Local demo key not found.' }
function Invoke-Signed([string]$Method,[string]$Path,[string]$Body='') {
    $taskTimestamp=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
    $taskNonce=[Guid]::NewGuid().ToString()
    $taskBytes=[Text.Encoding]::UTF8.GetBytes($Body)
    $taskHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($taskBytes)).ToLowerInvariant()
    $taskCanonical=$taskTimestamp+"`n"+$taskNonce+"`n"+$Method+"`n"+$Path+"`n"+$taskHash
    $taskMac=[Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($taskKey.secret))
    try { $taskSignature=[Convert]::ToHexString($taskMac.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskCanonical))).ToLowerInvariant() } finally { $taskMac.Dispose() }
    $taskHeaders=@{'X-Key-Id'=$taskKey.keyId;'X-Timestamp'=$taskTimestamp;'X-Nonce'=$taskNonce;'X-Signature'=$taskSignature;'X-Api-Version'='1.0'}
    $taskArguments=@{Uri=('http://127.0.0.1:8080'+$Path);Method=$Method;Headers=$taskHeaders;TimeoutSec=20}
    if($Method -eq 'POST') { $taskArguments.Body=$taskBytes;$taskArguments.ContentType='application/json' }
    Invoke-RestMethod @taskArguments
}
$taskBody=@{merchantOrderNo=$BusinessNo;amountMinor='10000';currency='CNY';channel='MOCK_A'} | ConvertTo-Json -Compress
$taskPayment=Invoke-Signed 'POST' '/api/v1/payments' $taskBody
for($taskAttempt=0;$taskAttempt -lt 30;$taskAttempt++) {
    $taskPayment=Invoke-Signed 'GET' ('/api/v1/payments/'+$taskPayment.id)
    if($taskPayment.status -in @('SUCCEEDED','FAILED','CLOSED')) { break }
    Start-Sleep -Milliseconds 500
}
$taskPayment | Select-Object id,merchantOrderNo,amountMinor,status,channel
