$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'ProcessEnvironment.ps1')
$taskPrefix='MERCHANTPAY_ENV_TEST_'+[Guid]::NewGuid().ToString('N')
$taskNames=@("${taskPrefix}_ABSENT","${taskPrefix}_EMPTY","${taskPrefix}_VALUE")
try {
    Remove-Item -LiteralPath ('Env:'+ $taskNames[0]) -ErrorAction SilentlyContinue
    [Environment]::SetEnvironmentVariable($taskNames[1],'','Process')
    [Environment]::SetEnvironmentVariable($taskNames[2],'original','Process')
    $taskSaved=@{};foreach($taskName in $taskNames){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
    foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,'changed','Process')}
    Restore-ProcessEnvironment $taskSaved
    if($null -ne [Environment]::GetEnvironmentVariable($taskNames[0],'Process')){throw 'Absent variables must be removed rather than restored as empty strings'}
    if([Environment]::GetEnvironmentVariable($taskNames[1],'Process') -cne $taskSaved[$taskNames[1]]){throw 'The original empty representation on this runtime must be preserved'}
    if([Environment]::GetEnvironmentVariable($taskNames[2],'Process') -cne 'original'){throw 'Original nonempty values must be restored'}
    Write-Output 'Process environment absence, empty value and original value restoration passed.'
} finally {foreach($taskName in $taskNames){Remove-Item -LiteralPath ('Env:'+ $taskName) -ErrorAction SilentlyContinue}}
