[CmdletBinding()]
param(
 [ValidateSet('status','create','recover','switch-primary')][string]$Mode='status',
 [Parameter(Mandatory)][ValidateCount(3,3)][string[]]$Targets,
 [string]$User='cluster_admin',[string]$Primary,
 [switch]$Apply,[switch]$WritersStopped,[string]$Confirmation
)
$ErrorActionPreference='Stop'
if($Apply -and $Mode -in @('recover','switch-primary') -and -not $WritersStopped){throw 'Stop and drain writers first; retain GTID and business-key evidence.'}
if($Mode -eq 'create' -and (-not $Apply -or $Confirmation -ne 'CREATE_NEW_EMPTY_V2_CLUSTER')){throw 'Creation needs -Apply and explicit empty isolated cluster confirmation.'}
$taskNames=@('V2_HA_MODE','V2_HA_TARGETS','V2_HA_USER','V2_HA_PRIMARY','V2_HA_CONFIRM','V2_HA_APPLY')
$taskPrevious=@{};foreach($taskName in $taskNames){$taskPrevious[$taskName]=[Environment]::GetEnvironmentVariable($taskName)}
try {
 $env:V2_HA_MODE=$Mode;$env:V2_HA_TARGETS=ConvertTo-Json -InputObject $Targets -Compress;$env:V2_HA_USER=$User
 $env:V2_HA_PRIMARY=$Primary;$env:V2_HA_CONFIRM=$Confirmation;$env:V2_HA_APPLY=$Apply.ToString().ToLowerInvariant()
 & mysqlsh --js --file (Join-Path $PSScriptRoot 'cluster.js')
 if($LASTEXITCODE -ne 0){throw "MySQL Shell exited $LASTEXITCODE; do not force bootstrap or retry money writes."}
} finally {foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskPrevious[$taskName])}}
