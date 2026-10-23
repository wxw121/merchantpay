$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'CapacityBudget.ps1')
if((Get-D1CapacityBudget 100GB 50GB 10GB).gatePassed){throw 'Post-load recovery and archive copies must retain 30% free space'}
if(-not (Get-D1CapacityBudget 100GB 70GB 10GB).gatePassed){throw 'Exact post-allocation 30% reserve should pass'}
if((Get-D1CapacityBudget 100GB 69GB 10GB).gatePassed){throw 'One GiB below the final reserve must fail'}
$taskBudget=Get-D1CapacityBudget 100GB 90GB 10GB 5GB
if(-not $taskBudget.gatePassed -or $taskBudget.archiveBytes -ne 10GB -or $taskBudget.restoreBytes -ne 10GB -or $taskBudget.loadBytes -ne 5GB -or $taskBudget.remainingBytes -ne 55GB){throw 'Budget must expose dataset, archive, restore and workload costs'}
Write-Output 'Full-data capacity and recovery budget guards passed.'
