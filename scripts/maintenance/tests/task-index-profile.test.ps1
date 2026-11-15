# Run in a separate PowerShell process. Docker is replaced here; no real container is touched.
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$taskWrapper=Join-Path $taskRoot 'scripts/maintenance/task-index-profile.ps1'
$taskEvidenceRoot=Join-Path $taskRoot ('.work/task-index-wrapper-tests-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskEvidenceRoot | Out-Null
function Reset-TaskIndexFixture {
    $global:TaskIndexFixture=@{indexes=@{PRIMARY='0|id';uq_outbox_event='0|event_id';ix_task_class_ready='1|queue_class,status,due_at,id';ix_task_class_lease='1|queue_class,status,lease_until,id';ix_task_merchant_time='1|merchant_id,created_at,id';ix_task_aggregate='1|aggregate_id,created_at';ix_task_archive_due='1|status,updated_at,id';ix_task_ready='1|status,due_at,id';ix_task_expired_lease='1|status,lease_until,id'};ddl=0;writer=$false;mysqlStopped=$false;notDrained=$false;ddlFailure=$false;postDdlDrift=$false;sqlCalls=0;endpoint='npipe:////./pipe/dockerDesktopLinuxEngine'}
}
function global:docker {
    $global:LASTEXITCODE=0
    if($args[0] -ne '--context' -or $args[1] -ne 'desktop-linux'){throw 'Docker context was not pinned'}
    switch($args[2]){
        'context' {@(@{Endpoints=@{docker=@{Host=$global:TaskIndexFixture.endpoint}}}) | ConvertTo-Json -Depth 5 -Compress;return}
        'ps' {'mysql-id';'platform-id';return}
        'inspect' {
            @(@{Name='/merchantpay-container-v1-mysql-1';State=@{Running=(-not $global:TaskIndexFixture.mysqlStopped);Health=@{Status='healthy'}}},@{Name='/merchantpay-container-v1-platform-1';State=@{Running=$global:TaskIndexFixture.writer}}) | ConvertTo-Json -Depth 5 -Compress
            return
        }
        'exec' {
            $taskSql=($input | Out-String)
            $global:TaskIndexFixture.sqlCalls++
            if($taskSql -match 'information_schema.statistics'){
                foreach($taskKey in ($global:TaskIndexFixture.indexes.Keys | Sort-Object)){
                    $taskParts=$global:TaskIndexFixture.indexes[$taskKey] -split '\|'
                    "$taskKey`t$($taskParts[0])`t$($taskParts[1])`t0"
                }
                return
            }
            if($taskSql -match "SELECT 'occupied_slots'"){
                foreach($taskKey in @('occupied_slots','counter_inflight','pending_tasks','nonterminal_payments','nonterminal_refunds')){
                    $taskValue=if($taskKey -eq 'pending_tasks' -and $global:TaskIndexFixture.notDrained){1}else{0}
                    "$taskKey`t$taskValue"
                }
                return
            }
            if($taskSql -match 'ALTER TABLE outbox_tasks'){
                $global:TaskIndexFixture.ddl++
                if($global:TaskIndexFixture.ddlFailure){$global:LASTEXITCODE=1;return}
                if($taskSql -match 'DROP INDEX'){
                    $global:TaskIndexFixture.indexes.Remove('ix_task_ready')
                    $global:TaskIndexFixture.indexes.Remove('ix_task_expired_lease')
                }else{
                    $global:TaskIndexFixture.indexes.ix_task_ready='1|status,due_at,id'
                    $global:TaskIndexFixture.indexes.ix_task_expired_lease='1|status,lease_until,id'
                }
                if($global:TaskIndexFixture.postDdlDrift){$global:TaskIndexFixture.indexes.Remove('ix_task_aggregate')}
                return
            }
            throw 'Unexpected fake SQL'
        }
        default {throw 'Unexpected fake Docker command'}
    }
}
$taskCases=@(
    @{name='trim';mode='Trim';ok=$true;ddl=1;setup={}},
    @{name='repeat-trim';mode='Trim';ok=$true;ddl=0;setup={$global:TaskIndexFixture.indexes.Remove('ix_task_ready');$global:TaskIndexFixture.indexes.Remove('ix_task_expired_lease')}},
    @{name='restore';mode='Restore';ok=$true;ddl=1;setup={$global:TaskIndexFixture.indexes.Remove('ix_task_ready');$global:TaskIndexFixture.indexes.Remove('ix_task_expired_lease')}},
    @{name='repeat-restore';mode='Restore';ok=$true;ddl=0;setup={}},
    @{name='writer-running';mode='Trim';ok=$false;ddl=0;setup={$global:TaskIndexFixture.writer=$true}},
    @{name='mysql-stopped';mode='Trim';ok=$false;ddl=0;setup={$global:TaskIndexFixture.mysqlStopped=$true}},
    @{name='not-drained';mode='Trim';ok=$false;ddl=0;setup={$global:TaskIndexFixture.notDrained=$true}},
    @{name='wrong-index';mode='Trim';ok=$false;ddl=0;setup={$global:TaskIndexFixture.indexes.ix_task_ready='1|status,id'}},
    @{name='missing-unique';mode='Trim';ok=$false;ddl=0;setup={$global:TaskIndexFixture.indexes.Remove('uq_outbox_event')}},
    @{name='partial';mode='Trim';ok=$false;ddl=0;setup={$global:TaskIndexFixture.indexes.Remove('ix_task_ready')}},
    @{name='ddl-failed';mode='Trim';ok=$false;ddl=1;setup={$global:TaskIndexFixture.ddlFailure=$true}},
    @{name='post-ddl-drift';mode='Trim';ok=$false;ddl=1;setup={$global:TaskIndexFixture.postDdlDrift=$true}},
    @{name='workspace-locked';mode='Trim';ok=$false;ddl=0;locked=$true;setup={}},
    @{name='remote-context';mode='Trim';ok=$false;ddl=0;setup={$global:TaskIndexFixture.endpoint='ssh://remote.example'}}
)
try{
    foreach($taskCase in $taskCases){
        Reset-TaskIndexFixture
        & $taskCase.setup
        $taskPath=Join-Path $taskEvidenceRoot ($taskCase.name+'.json')
        $taskCaught=$null
        $taskHeldLock=$null
        try{
            if($taskCase.locked){$taskHeldLock=[IO.File]::Open((Join-Path $taskRoot '.work/performance-baseline.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}
            & $taskWrapper -Mode $taskCase.mode -EvidencePath $taskPath | Out-Null
        }catch{$taskCaught=$_.Exception.Message}
        finally{if($taskHeldLock){$taskHeldLock.Dispose()}}
        if(-not (Test-Path -LiteralPath $taskPath)){throw ('Missing evidence: '+$taskCase.name+'; '+$taskCaught)}
        $taskResult=Get-Content -LiteralPath $taskPath -Raw | ConvertFrom-Json
        if($taskResult.passed -ne $taskCase.ok -or $global:TaskIndexFixture.ddl -ne $taskCase.ddl -or [bool]$taskCaught -eq $taskCase.ok){throw ('Case failed: '+$taskCase.name+'; '+$taskCaught)}
        if(-not $taskCase.ok -and -not $taskResult.failure){throw ('Failure not recorded: '+$taskCase.name)}
        Write-Output ('PASS '+$taskCase.name)
    }
    Write-Output ('All '+$taskCases.Count+' wrapper checks passed; evidence='+$taskEvidenceRoot)
}finally{
    Remove-Item Function:\docker
    Remove-Variable TaskIndexFixture -Scope Global
}
