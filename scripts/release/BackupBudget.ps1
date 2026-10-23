function Test-LocalBackupArchiveBudget([long]$PlainBytes,[long]$ArchiveBytes,[ValidateSet('container','native')][string]$Source='container') {
    $taskLimit=1GB
    return $PlainBytes -ge 0 -and $ArchiveBytes -gt 0 -and $ArchiveBytes -le $taskLimit
}
