param([string]$Url, [long]$Length, [string]$Sha256, [string]$Name)
$ErrorActionPreference = 'Stop'
if ($Name -notmatch '^[a-z0-9-]{1,32}$' -or $Length -lt 1 -or $Length -gt 2147483647 -or $Sha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid ranged download metadata.' }
$taskRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskDirectory = Join-Path $taskRoot ('.tools\downloads\' + $Name + '-parts')
New-Item -ItemType Directory -Path $taskDirectory -Force | Out-Null
$taskChunkSize = 1048576L
$taskCount = [int][Math]::Ceiling($Length / [double]$taskChunkSize)
0..($taskCount - 1) | ForEach-Object -ThrottleLimit 8 -Parallel {
    $ErrorActionPreference = 'Stop'
    $taskIndex = $_
    $taskPart = Join-Path $using:taskDirectory ('{0:D4}.bin' -f $taskIndex)
    $taskStart = $taskIndex * $using:taskChunkSize
    $taskEnd = [Math]::Min($taskStart + $using:taskChunkSize - 1, $using:Length - 1)
    $taskExpected = $taskEnd - $taskStart + 1
    if ((Test-Path -LiteralPath $taskPart) -and ((Get-Item -LiteralPath $taskPart).Length -eq $taskExpected)) { return }
    & curl.exe --silent --show-error --location --fail --retry 2 --range ($taskStart.ToString() + '-' + $taskEnd.ToString()) --max-time 90 --output $taskPart $using:Url
    if ($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath $taskPart).Length -ne $taskExpected) { throw ('Range failed: ' + $taskIndex) }
}
$taskArchive = Join-Path $taskRoot ('.tools\downloads\' + $Name + '-ranged.zip')
$taskOutput = [System.IO.File]::Create($taskArchive)
try {
    for ($taskIndex=0; $taskIndex -lt $taskCount; $taskIndex++) {
        $taskInput = [System.IO.File]::OpenRead((Join-Path $taskDirectory ('{0:D4}.bin' -f $taskIndex)))
        try { $taskInput.CopyTo($taskOutput) } finally { $taskInput.Dispose() }
    }
} finally { $taskOutput.Dispose() }
if ((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash -ne $Sha256) { throw 'Final archive checksum mismatch.' }
$taskDestination = Join-Path $taskRoot ('.tools\' + $Name)
if (-not (Test-Path -LiteralPath $taskDestination)) { Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskDestination }
Write-Output ('Verified and extracted ' + $Name)
