[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskWork=Join-Path $taskRoot '.work/native-static'
New-Item -ItemType Directory -Path $taskWork -Force | Out-Null
$taskPrevious=@{}
foreach ($taskName in @('JAVA_TOOL_OPTIONS','NODE_OPTIONS','UV_THREADPOOL_SIZE')) { $taskPrevious[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process') }
Push-Location $taskRoot
try {
    $env:JAVA_TOOL_OPTIONS='-Xmx384m -XX:ActiveProcessorCount=1 -XX:TieredStopAtLevel=1'
    $env:NODE_OPTIONS='--max-old-space-size=384'
    $env:UV_THREADPOOL_SIZE='1'
    & (Join-Path $PSScriptRoot 'mvn.ps1') -MavenArguments @('-o','-pl','platform','test-compile','-DskipTests','-B','-ntp') *> (Join-Path $taskWork 'java-compile.log')
    if ($LASTEXITCODE -ne 0) { throw 'Java static compilation failed; inspect .work/native-static/java-compile.log.' }
    $taskNode=(Get-ChildItem -LiteralPath '.tools/node24' -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'node.exe') } | Select-Object -First 1).FullName
    if (-not $taskNode) { throw 'Node toolchain is missing.' }
    & (Join-Path $taskNode 'node.exe') 'console/node_modules/typescript/bin/tsc' --noEmit -p console/tsconfig.json *> (Join-Path $taskWork 'frontend-types.log')
    if ($LASTEXITCODE -ne 0) { throw 'Frontend type check failed; inspect .work/native-static/frontend-types.log.' }
    foreach ($taskScript in (Get-ChildItem -LiteralPath 'scripts' -Recurse -Filter '*.ps1')) {
        $taskTokens=$null; $taskErrors=$null
        [Management.Automation.Language.Parser]::ParseFile($taskScript.FullName,[ref]$taskTokens,[ref]$taskErrors) | Out-Null
        if ($taskErrors.Count -gt 0) { throw ('PowerShell parse failed: '+$taskScript.FullName) }
    }
    Get-Content -LiteralPath 'infra/ha/r2-acceptance.json' -Raw | ConvertFrom-Json | Out-Null
    Write-Output 'Static Java/test-source compilation, frontend types, PowerShell parsing and R2 JSON passed. No tests, services, Docker or network commands were run.'
} finally {
    Pop-Location
    foreach ($taskName in $taskPrevious.Keys) { [Environment]::SetEnvironmentVariable($taskName,$taskPrevious[$taskName],'Process') }
}
