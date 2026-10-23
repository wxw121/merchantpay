function Test-LocalBackupArchiveBudget([long]$PlainBytes,[long]$ArchiveBytes,[ValidateSet('container','native')][string]$Source='container') {
    $taskLimit=if($Source -eq 'native'){1GB}else{256MB}
    return $PlainBytes -ge 0 -and $ArchiveBytes -gt 0 -and $ArchiveBytes -le $taskLimit
}
