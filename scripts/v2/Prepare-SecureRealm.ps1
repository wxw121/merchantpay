[CmdletBinding()]
param([Parameter(Mandatory)][string]$InputRealm,[Parameter(Mandatory)][string]$OutputRealm,[Parameter(Mandatory)][uri]$ConsoleOrigin)
$ErrorActionPreference='Stop'
if($ConsoleOrigin.Scheme -ne 'https' -or $ConsoleOrigin.AbsolutePath -ne '/' -or $ConsoleOrigin.Query -or $ConsoleOrigin.UserInfo){throw 'Use a fixed HTTPS console origin.'}
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskRealm=Get-Content -LiteralPath $InputRealm -Raw | ConvertFrom-Json -AsHashtable
$taskOverlay=Get-Content -LiteralPath (Join-Path $taskRoot 'infra/keycloak/v2-security-overlay.json') -Raw | ConvertFrom-Json -AsHashtable
foreach($taskKey in $taskOverlay.Keys){$taskRealm[$taskKey]=$taskOverlay[$taskKey]}
foreach($taskClient in $taskRealm.clients){if($taskClient.clientId -eq 'merchantpay-console'){$taskClient.redirectUris=@($ConsoleOrigin.GetLeftPart([UriPartial]::Authority)+'/login/oauth2/code/keycloak');$taskClient.webOrigins=@($ConsoleOrigin.GetLeftPart([UriPartial]::Authority));$taskClient.attributes['default.acr.values']='1';$taskClient.attributes['minimum.acr.value']='1'}}
foreach($taskUser in $taskRealm.users){if(@($taskUser.realmRoles | Where-Object {$_ -in @('ADMIN','OPS','FINANCE')}).Count -gt 0){$taskUser['requiredActions']=@('CONFIGURE_TOTP')}}
foreach($taskUser in $taskRealm.users){foreach($taskCredential in $taskUser.credentials){if($taskCredential.type -eq 'password' -and ($taskCredential.value.Length -lt 12 -or $taskCredential.value -match '^(__.*__|admin|password)$')){throw 'Replace every demo password in the private input realm before preparing an HTTPS realm.'}}}
# Writes a new import artifact only. Does not contact or alter a running identity provider.
if(Test-Path -LiteralPath $OutputRealm){throw 'Output already exists; choose a fresh private file.'}
[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputRealm),($taskRealm | ConvertTo-Json -Depth 40),[Text.UTF8Encoding]::new($false))
Write-Output 'Secure realm artifact prepared. Keep generated secrets outside Git; verify OTP and ACR2 before enabling traffic.'
