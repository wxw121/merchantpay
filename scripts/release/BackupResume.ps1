function Assert-NativeBackupLocalObjects([string]$Project) {
    $taskReferences=& docker exec ($Project+'-mysql-1') sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u merchantpay -N merchantpay -e "SELECT (SELECT COUNT(*) FROM recon_batches WHERE storage_key LIKE CHAR(115,51,58,37))+(SELECT COUNT(*) FROM exports WHERE storage_key LIKE CHAR(115,51,58,37))"'
    if($LASTEXITCODE -ne 0 -or $taskReferences -ne '0'){throw 'Native source contains S3 references or its references could not be verified; no incomplete local-only set was published'}
}

function Start-BackupNativeSource([string]$Root,[string]$NativeMode) {
    # This legacy backup flow already operates Docker MySQL/Keycloak containers.
    $taskArguments=@{SkipBuild=$true;Docker=$true}
    if($NativeMode -eq 'performance'){$taskArguments.Performance=$true}
    # Invoke the PowerShell script in-process: a nested native pwsh pipeline can retain daemon handles after its launcher exits.
    & (Join-Path $Root 'scripts/start-local.ps1') @taskArguments *> (Join-Path $Root '.work/native-backup-resume.log')
}

function Resume-LocalBackupSource([string]$Source,[string[]]$Containers,[bool]$NativeStopped,[string]$NativeMode,[string]$Root) {
    if($Containers.Count -gt 0){& docker start @Containers | Out-Null;if($LASTEXITCODE -ne 0){throw 'Stopped source containers could not resume'}}
    $taskNative=$Source -eq 'native'
    $taskIdentityUrl=if($taskNative){'http://127.0.0.1:8180'}else{'http://127.0.0.1:18180'}
    if($NativeStopped -or @($Containers|Where-Object {$_ -like '*-keycloak-1'}).Count -gt 0) {
        $taskReady=$false
        for($taskAttempt=0;$taskAttempt -lt 60;$taskAttempt++){try{Invoke-RestMethod ($taskIdentityUrl+'/realms/merchantpay/protocol/openid-connect/certs') -TimeoutSec 2|Out-Null;$taskReady=$true;break}catch{};Start-Sleep -Seconds 1}
        if(-not $taskReady){throw 'Source identity server did not become ready'}
    }
    if($NativeStopped) {
        Start-BackupNativeSource -Root $Root -NativeMode $NativeMode
    }
    $taskEndpoints=@()
    foreach($taskService in @('platform','simulator','console')) {
        if(-not $NativeStopped -and @($Containers|Where-Object {$_ -like ('*-'+$taskService+'-1')}).Count -eq 0){continue}
        $taskPort=if($taskNative){@{platform=8080;simulator=9091;console=5173}[$taskService]}else{@{platform=18080;simulator=19091;console=15173}[$taskService]}
        $taskEndpoints+=@{url=('http://127.0.0.1:'+$taskPort+$(if($taskService -eq 'console'){'/'}else{'/actuator/health'}));health=$taskService -ne 'console'}
    }
    foreach($taskEndpoint in $taskEndpoints) {
        $taskReady=$false
        for($taskAttempt=0;$taskAttempt -lt 60;$taskAttempt++) {
            try {
                if($taskEndpoint.health){$taskReady=(Invoke-RestMethod $taskEndpoint.url -TimeoutSec 2).status -eq 'UP'}else{$taskReady=(Invoke-WebRequest $taskEndpoint.url -TimeoutSec 2).StatusCode -eq 200}
                if($taskReady){break}
            }catch{}
            Start-Sleep -Seconds 1
        }
        if(-not $taskReady){throw ('Resumed source did not become healthy: '+$taskEndpoint.url)}
    }
}
