[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskEnvPath = Join-Path $taskRoot '.env'
if (-not (Test-Path -LiteralPath $taskEnvPath)) {
    $taskLines = [Collections.Generic.List[string]]::new()
    foreach ($taskName in @('DATABASE_PASSWORD','MYSQL_ROOT_PASSWORD','OIDC_CLIENT_SECRET','CHANNEL_TOKEN','CALLBACK_SECRET','DEMO_PASSWORD','KEYCLOAK_ADMIN_PASSWORD','METRICS_PASSWORD')) {
        $taskBytes = [byte[]]::new(32)
        $taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
        $taskValue = [Convert]::ToBase64String($taskBytes).TrimEnd('=').Replace('+','-').Replace('/','_')
        $taskLines.Add($taskName + '=' + $taskValue)
    }
    $taskBytes = [byte[]]::new(32)
    $taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
    $taskLines.Add('MASTER_KEY=' + [Convert]::ToBase64String($taskBytes))
    $taskLines.Add('LOCAL_WEBHOOK=http://127.0.0.1:9091/merchant-webhook')
    [IO.File]::WriteAllLines($taskEnvPath,$taskLines,[Text.UTF8Encoding]::new($false))
}
$taskValues = @{}
foreach ($taskLine in [IO.File]::ReadAllLines($taskEnvPath)) {
    if ($taskLine -match '^([A-Z_]+)=(.*)$') { $taskValues[$Matches[1]] = $Matches[2] }
}
$taskRealm = [IO.File]::ReadAllText((Join-Path $taskRoot 'infra\keycloak\realm.template.json'))
$taskRealm = $taskRealm.Replace('__CLIENT_SECRET__',$taskValues['OIDC_CLIENT_SECRET']).Replace('__DEMO_PASSWORD__',$taskValues['DEMO_PASSWORD'])
$taskRealmDirectory = Join-Path $taskRoot '.data\keycloak'
New-Item -ItemType Directory -Path $taskRealmDirectory -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $taskRealmDirectory 'realm.json'),$taskRealm,[Text.UTF8Encoding]::new($false))
Write-Output 'Local configuration ready. Secrets remain in .env and .data; existing secrets were preserved.'
$taskPrometheusDirectory = Join-Path $taskRoot '.data\prometheus'
New-Item -ItemType Directory -Path $taskPrometheusDirectory -Force | Out-Null
$taskPrometheus = [IO.File]::ReadAllText((Join-Path $taskRoot 'infra\prometheus\prometheus.template.yml')).Replace('__METRICS_PASSWORD__',$taskValues['METRICS_PASSWORD'])
[IO.File]::WriteAllText((Join-Path $taskPrometheusDirectory 'prometheus.yml'),$taskPrometheus,[Text.UTF8Encoding]::new($false))
