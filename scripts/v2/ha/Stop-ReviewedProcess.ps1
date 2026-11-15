[CmdletBinding(SupportsShouldProcess,ConfirmImpact='High')]
param([Parameter(Mandatory)][int]$TargetPid,[Parameter(Mandatory)][datetime]$ExpectedStartTime,[Parameter(Mandatory)][string]$ExpectedExecutable,[Parameter(Mandatory)][ValidateSet('api','fast-worker','notify-worker','bulk-worker','projection-worker')][string]$Role,[Parameter(Mandatory)][string]$RunId,[switch]$Execute)
$ErrorActionPreference='Stop'
$taskProcess=Get-Process -Id $TargetPid -ErrorAction Stop
if($taskProcess.Id -eq $PID -or $taskProcess.StartTime.ToUniversalTime() -ne $ExpectedStartTime.ToUniversalTime() -or [IO.Path]::GetFullPath($taskProcess.Path) -ne [IO.Path]::GetFullPath($ExpectedExecutable)){throw 'PID identity changed; refusing to stop another process.'}
if(-not $Execute){Write-Output "Prepared fault $RunId for $Role PID $TargetPid; no process stopped.";return}
if($PSCmdlet.ShouldProcess("$Role PID $TargetPid started $ExpectedStartTime","Stop one reviewed acceptance process; preserve data and reconcile original keys")){Stop-Process -Id $TargetPid -ErrorAction Stop}
