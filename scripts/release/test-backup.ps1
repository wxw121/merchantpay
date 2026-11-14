[CmdletBinding()]
param([ValidatePattern('^[a-z0-9-]{1,24}$')][string]$ExistingId)
$ErrorActionPreference='Stop'
if(-not $IsWindows){throw 'DPAPI checks require the Windows account used for local backups'}
. (Join-Path $PSScriptRoot 'BackupBudget.ps1')
if(-not (Test-LocalBackupArchiveBudget 1GB 64MB)){throw 'DPAPI memory budget must count the compressed buffer rather than all source files'}
if(-not (Test-LocalBackupArchiveBudget 2GB 512MB 'container')){throw 'The grown container data set requires a bounded 1GiB compressed budget'}
if(Test-LocalBackupArchiveBudget 2GB 1025MB 'container'){throw 'Container archives beyond 1GiB must still fail closed'}
if(-not (Test-LocalBackupArchiveBudget 2GB 640MB 'native')){throw 'Measured native historical sets require a bounded 1GiB compressed budget'}
if(Test-LocalBackupArchiveBudget 2GB 1025MB 'native'){throw 'Native archives beyond 1GiB must still fail closed'}
$taskPlain=[Security.Cryptography.RandomNumberGenerator]::GetBytes(1024)
$taskEncrypted=[Security.Cryptography.ProtectedData]::Protect($taskPlain,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
$taskRestored=[Security.Cryptography.ProtectedData]::Unprotect($taskEncrypted,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
if(-not [Security.Cryptography.CryptographicOperations]::FixedTimeEquals($taskPlain,$taskRestored)){throw 'Backup encryption round trip failed'}
$taskEncrypted[[int]($taskEncrypted.Length/2)]=$taskEncrypted[[int]($taskEncrypted.Length/2)] -bxor 1
$taskRejected=$false
try {[Security.Cryptography.ProtectedData]::Unprotect($taskEncrypted,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)|Out-Null}catch{$taskRejected=$true}
if(-not $taskRejected){throw 'Corrupted encrypted backup must not decrypt successfully'}
if($ExistingId) {
    $taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $taskBundle=Join-Path $taskRoot ('.data/backups/'+$ExistingId+'.dpapi')
    $taskBefore=(Get-FileHash -LiteralPath $taskBundle).Hash
    $taskRejected=$false
    try {& (Join-Path $PSScriptRoot 'backup-local.ps1') -Id $ExistingId}catch{if($_.Exception.Message -notlike '*immutable*'){throw};$taskRejected=$true}
    if(-not $taskRejected -or (Get-FileHash -LiteralPath $taskBundle).Hash -ne $taskBefore){throw 'Repeated ID replaced an immutable backup'}
}
Write-Output 'Backup encryption round trip, tamper rejection and requested immutable-ID guards passed.'
