# Native argv check only: a transient 0.25CPU/32MiB shell, no network or database.
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$taskText=Get-Content (Join-Path $taskRoot 'scripts/maintenance/task-index-profile.ps1') -Raw
$taskCommand=[regex]::Match($taskText,"sh -c '([^']+)'").Groups[1].Value
if(-not $taskCommand.EndsWith('exec mysql -u root -N -B merchantpay')){throw 'Could not locate wrapper shell command'}
$taskProbeCommand=$taskCommand.Replace('exec mysql -u root -N -B merchantpay','printenv MYSQL_PWD')
$taskPrevious=[Environment]::GetEnvironmentVariable('MYSQL_ROOT_PASSWORD','Process')
try{
    $taskExpected='space ''quote "double $literal*?[;end'
    $env:MYSQL_ROOT_PASSWORD=$taskExpected
    $taskActual=& docker --context desktop-linux run --rm --pull never --network none --cpus 0.25 --memory 32m --env MYSQL_ROOT_PASSWORD --entrypoint sh merchantpay/mysql:v1-local -c $taskProbeCommand
    if($LASTEXITCODE -ne 0 -or $taskActual -cne $taskExpected){throw 'Native shell password assignment did not preserve the fixture'}
    Write-Output ('Native shell assignment passed on PowerShell '+$PSVersionTable.PSVersion)
}finally{[Environment]::SetEnvironmentVariable('MYSQL_ROOT_PASSWORD',$taskPrevious,'Process')}
