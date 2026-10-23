[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
$taskToken = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8180/realms/master/protocol/openid-connect/token' -Body @{grant_type='password';client_id='admin-cli';username='local-admin';password=$env:KEYCLOAK_ADMIN_PASSWORD}
$taskHeaders = @{Authorization=('Bearer '+$taskToken.access_token)}
foreach ($taskUsername in @('admin','merchant-a','merchant-b','finance','auditor','ops')) {
    $taskUsers = Invoke-RestMethod -Uri ('http://127.0.0.1:8180/admin/realms/merchantpay/users?exact=true&username='+$taskUsername) -Headers $taskHeaders
    if ($taskUsers.Count -ne 1) { throw ('Expected exactly one project demo user: '+$taskUsername) }
    $taskUser = $taskUsers[0]
    $taskUser | Add-Member -NotePropertyName email -NotePropertyValue ($taskUsername+'@merchantpay.invalid') -Force
    $taskUser.emailVerified = $true
    $taskBody = $taskUser | ConvertTo-Json -Depth 20
    Invoke-RestMethod -Method Put -Uri ('http://127.0.0.1:8180/admin/realms/merchantpay/users/'+$taskUser.id) -Headers $taskHeaders -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($taskBody)) | Out-Null
}
Write-Output 'Project demo identity profiles synchronized; credentials and roles were preserved.'
