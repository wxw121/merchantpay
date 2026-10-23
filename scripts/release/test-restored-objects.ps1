[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]{1,24}$')][string]$Id)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $taskRoot 'scripts/ProcessEnvironment.ps1')
$taskFolder=Join-Path $taskRoot ('.data/restores/'+$Id)
$taskContainer='merchantpay-restore-'+$Id+'-db'
$taskSaved=@{};foreach($taskName in @('DATABASE_URL','DATABASE_PASSWORD','MASTER_KEY','RESTORE_HAS_S3')){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
$taskWasRunning=$false;$taskStartedHere=$false
Push-Location $taskRoot
try {
    $taskFacts=Get-Content ('.work/release/restore-'+$Id+'-facts.json') -Raw|ConvertFrom-Json
    if($taskFacts.referencedS3BillsVerified -le 0 -or $taskFacts.referencedS3ExportsVerified -le 0){throw 'This negative check requires an already verified nonempty S3 recovery set'}
    $taskOwner=& docker inspect --format '{{index .Config.Labels "dev.merchantpay.restore"}}' $taskContainer
    if($LASTEXITCODE -ne 0 -or $taskOwner -ne $Id){throw 'Refusing to start a database outside the explicit isolated restore'}
    $taskWasRunning=(& docker inspect --format '{{.State.Running}}' $taskContainer) -eq 'true'
    if($LASTEXITCODE -ne 0){throw 'Cannot establish original isolated-container state'}
    & docker start $taskContainer|Out-Null
    if($LASTEXITCODE -ne 0){throw 'Isolated restored database unavailable'}
    $taskStartedHere=-not $taskWasRunning
    $taskReady=$false
    for($taskTry=0;$taskTry -lt 30;$taskTry++) {& docker exec $taskContainer sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h127.0.0.1 -u merchantpay -N -e "SELECT 1" merchantpay' 2>$null|Out-Null;if($LASTEXITCODE -eq 0){$taskReady=$true;break};Start-Sleep -Seconds 1}
    if(-not $taskReady){throw 'Isolated restore database did not become ready'}
    foreach($taskLine in Get-Content (Join-Path $taskFolder 'runtime.env')){if($taskLine -match '^(DATABASE_PASSWORD|MASTER_KEY)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
    $env:DATABASE_URL='jdbc:mysql://127.0.0.1:13309/merchantpay?connectionTimeZone=UTC';$env:RESTORE_HAS_S3='false'
    $taskJava=(Get-ChildItem '.tools/java25' -Directory|Select-Object -First 1).FullName
    $taskRuntime=(Get-Content 'platform/target/storage-classpath.txt' -Raw).Trim()
    $taskH2=(Get-ChildItem '.tools/m2/com/h2database/h2' -Recurse -Filter '*.jar'|Select-Object -First 1).FullName
    $taskCp=@('.work/restore-classes','platform/target/classes','simulator/target/classes',$taskRuntime,$taskH2)-join [IO.Path]::PathSeparator
    $taskLog=Join-Path $taskRoot ('.work/release/restore-'+$Id+'-s3-disabled.log')
    & (Join-Path $taskJava 'bin/java.exe') -cp $taskCp RestoredSetCheck $taskFolder (Join-Path $taskRoot '.work/release/rejected-restore-facts.json') *> $taskLog
    $taskExit=$LASTEXITCODE
    if($taskExit -eq 0 -or -not ([IO.File]::ReadAllText($taskLog).Contains('S3 object requires configured S3 storage'))){throw 'Missing S3 configuration did not fail with the expected object-storage boundary'}
    @{at=[DateTimeOffset]::UtcNow;backupId=$Id;s3DisabledRejected=$true;sourceDataModified=$false;unexpectedLocalFallback=$false;exitCode=$taskExit}|ConvertTo-Json|Set-Content ('.work/release/restore-'+$Id+'-negative.json')
    Write-Output 'S3 references reject local-only recovery; financial rows and source services were not modified.'
} finally {
    if($taskStartedHere -and $taskOwner -eq $Id){& docker stop -t 15 $taskContainer|Out-Null}
    Restore-ProcessEnvironment $taskSaved;Pop-Location
}
