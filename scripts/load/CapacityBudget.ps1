function Get-D1CapacityBudget([long]$TotalBytes,[long]$FreeBytes,[long]$ProjectedBytes,[long]$LoadBytes=10GB) {
    if($TotalBytes -le 0 -or $FreeBytes -lt 0 -or $FreeBytes -gt $TotalBytes -or $ProjectedBytes -le 0 -or $LoadBytes -lt 0){throw 'Invalid capacity inputs'}
    # Conservatively reserve an uncompressed archive and an isolated restore on this drive.
    $taskRequired=3D*$ProjectedBytes+$LoadBytes
    $taskRemaining=[decimal]$FreeBytes-$taskRequired
    [pscustomobject]@{totalBytes=$TotalBytes;freeBytes=$FreeBytes;datasetBytes=$ProjectedBytes;archiveBytes=$ProjectedBytes;restoreBytes=$ProjectedBytes;loadBytes=$LoadBytes;requiredAllocationBytes=$taskRequired;remainingBytes=$taskRemaining;remainingFraction=$taskRemaining/$TotalBytes;minimumFreeFraction=.3;gatePassed=($taskRemaining/$TotalBytes -ge .3)}
}
