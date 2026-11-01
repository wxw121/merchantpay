[CmdletBinding()]
param([Parameter(Mandatory)][uri]$BaseUrl,[Parameter(Mandatory)][string]$ConfirmTarget,[Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9-]{1,50}$')][string]$RunId,[ValidateRange(1,3600)][int]$Seconds=15,[ValidateRange(0,2000)][int]$PayRate=5,[ValidateRange(0,8000)][int]$ReadRate=0,[string]$QueryIds,[switch]$Execute)
$ErrorActionPreference='Stop'
if(-not $Execute){throw 'No load started. Supply -Execute only on an approved isolated acceptance host.'}
if($BaseUrl.Scheme -ne 'https' -or $ConfirmTarget -ne $BaseUrl.Host){throw 'Explicit HTTPS acceptance target confirmation required.'}
if(-not $env:R2_KEY_ID -or -not $env:R2_KEY_SECRET){throw 'Set dedicated isolated merchant credentials in the process environment.'}
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskOutput=Join-Path $taskRoot '.work/v2-r2';New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
$env:R2_BASE_URL=$BaseUrl.AbsoluteUri;$env:R2_CONFIRM_TARGET=$ConfirmTarget;$env:R2_RUN_ID=$RunId;$env:R2_SECONDS="$Seconds";$env:R2_PAY_RATE="$PayRate";$env:R2_READ_RATE="$ReadRate";$env:R2_OUTPUT=$taskOutput;$env:R2_QUERY_IDS=$QueryIds
$taskJava=Join-Path $taskRoot '.tools/java25/jdk-25.0.5/bin/java.exe'
& $taskJava -Xmx384m -XX:ActiveProcessorCount=1 -XX:TieredStopAtLevel=1 (Join-Path $PSScriptRoot 'R2Load.java')
if($LASTEXITCODE -ne 0){throw 'Generator failed; preserve accepted business keys and reconcile before any retry.'}
