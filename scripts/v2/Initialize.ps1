[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
& (Join-Path $taskRoot 'scripts/init-local.ps1')
$taskEnv=Join-Path $taskRoot '.env'
if(-not ([IO.File]::ReadAllLines($taskEnv) | Where-Object { $_ -match '^V2_CDC_PASSWORD=' })) {
    $taskBytes=[Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $taskSecret=[Convert]::ToHexString($taskBytes).ToLowerInvariant()
    [IO.File]::AppendAllText($taskEnv,[Environment]::NewLine+'V2_CDC_PASSWORD='+$taskSecret+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
}
$taskDirectory=Join-Path $taskRoot '.data/v2/keycloak'
New-Item -ItemType Directory -Path $taskDirectory -Force | Out-Null
$taskRealm=[IO.File]::ReadAllText((Join-Path $taskRoot '.data/keycloak/realm.json'))
$taskRealm=$taskRealm.Replace('http://127.0.0.1:8080/login/oauth2/code/keycloak','http://127.0.0.1:15173/login/oauth2/code/keycloak').Replace('http://127.0.0.1:5173','http://127.0.0.1:15173')
[IO.File]::WriteAllText((Join-Path $taskDirectory 'realm.json'),$taskRealm,[Text.UTF8Encoding]::new($false))
Write-Output 'V2 local secrets and realm ready. Use a separate merchantpay-container-v2 Compose project.'
