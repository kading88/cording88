. (Join-Path $PSScriptRoot 'scripts\common.ps1')
$nodeExe = Join-Path $ToolsDir 'node\node.exe'
$npmCli = Join-Path $ToolsDir 'node\node_modules\npm\bin\npm-cli.js'
$javaExe = Get-ProjectJava
$env:JAVA_HOME = Split-Path (Split-Path $javaExe -Parent) -Parent
$env:PATH = (Split-Path $nodeExe -Parent) + ';' + (Split-Path $javaExe -Parent) + ';' + $env:PATH
$maven = Get-Command mvn.cmd -ErrorAction Stop
Push-Location (Join-Path $ProjectRoot 'frontend')
try {
    if (Test-Path -LiteralPath 'package-lock.json') { & $nodeExe $npmCli ci --cache (Join-Path $ToolsDir 'npm-cache') --no-audit --no-fund }
    else { & $nodeExe $npmCli install --cache (Join-Path $ToolsDir 'npm-cache') --no-audit --no-fund }
    Assert-LastExit 'Frontend dependencies'
    & $nodeExe $npmCli run build
    Assert-LastExit 'Frontend build'
} finally { Pop-Location }
& $maven.Source -f (Join-Path $ProjectRoot 'backend\pom.xml') "-Dmaven.repo.local=$(Join-Path $ToolsDir 'm2')" '-Dmaven.test.skip=true' package
Assert-LastExit 'Backend build'
Write-Host 'Build complete: backend\target\scx-review-1.0.0.jar'
