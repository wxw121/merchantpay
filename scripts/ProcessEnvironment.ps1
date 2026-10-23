function Restore-ProcessEnvironment([hashtable]$Saved) {
    foreach($taskName in $Saved.Keys) {
        if($null -eq $Saved[$taskName]) {Remove-Item -LiteralPath ('Env:'+ $taskName) -ErrorAction SilentlyContinue}
        else {[Environment]::SetEnvironmentVariable($taskName,$Saved[$taskName],'Process')}
    }
}
