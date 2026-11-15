[CmdletBinding()]
param([string]$Project='merchantpay-container-v2')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location -LiteralPath $taskRoot
. './scripts/Import-LocalEnvironment.ps1' -EnvironmentFile '.env'
if($env:V2_CDC_PASSWORD -notmatch '^[a-zA-Z0-9_-]{32,128}$'){throw 'Run scripts/v2/Initialize.ps1 to create a dedicated CDC secret.'}
$taskCompose=@('compose','--env-file','.env','-p',$Project,'-f','infra/compose.yaml','-f','infra/compose.app.yaml','-f','infra/compose.v2.yaml')
$taskSql="CREATE USER IF NOT EXISTS 'merchantpay_cdc'@'%' IDENTIFIED BY '$($env:V2_CDC_PASSWORD)'; ALTER USER 'merchantpay_cdc'@'%' IDENTIFIED BY '$($env:V2_CDC_PASSWORD)'; GRANT SELECT, LOCK TABLES ON merchantpay.* TO 'merchantpay_cdc'@'%'; GRANT RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'merchantpay_cdc'@'%';"
$taskSql | & docker @taskCompose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
if($LASTEXITCODE -ne 0){throw 'CDC database user provisioning failed.'}
& docker @taskCompose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic outbox.event.merchantpay --partitions 3 --replication-factor 1
if($LASTEXITCODE -ne 0){throw 'Event topic provisioning failed.'}
$taskConfig=[IO.File]::ReadAllText((Join-Path $taskRoot 'infra/connect/outbox.json'))
Invoke-RestMethod -Method Put -Uri 'http://127.0.0.1:18083/connectors/merchantpay-outbox/config' -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($taskConfig)) | Out-Null
Write-Output 'CDC connector installed with environment secret references. Inspect /connectors/merchantpay-outbox/status before switching transport.'
