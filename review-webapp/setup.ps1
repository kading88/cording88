param([string]$PythonExecutable = 'python')
. (Join-Path $PSScriptRoot 'scripts\common.ps1')
Set-Location $ProjectRoot
New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
if (-not (Test-Path -LiteralPath '.env')) {
    $dbSecret = [Guid]::NewGuid().ToString('N')
    $rootSecret = [Guid]::NewGuid().ToString('N')
    $adminSecret = 'Scx-' + [Guid]::NewGuid().ToString('N').Substring(0, 14)
    $reviewerSecret = 'Scx-' + [Guid]::NewGuid().ToString('N').Substring(0, 14)
    $envText = @"
DB_HOST=127.0.0.1
DB_PORT=3307
DB_NAME=scx_review
DB_USER=scx_app
DB_PASSWORD=$dbSecret
MYSQL_ROOT_PASSWORD=$rootSecret
ADMIN_PASSWORD=$adminSecret
REVIEWER_PASSWORD=$reviewerSecret
APP_HOST=127.0.0.1
APP_PORT=8088
COOKIE_SECURE=false
"@
    [IO.File]::WriteAllText((Join-Path $ProjectRoot '.env'), $envText, [Text.UTF8Encoding]::new($false))
}
Read-ProjectEnv
$javaExe = Get-ProjectJava
$env:JAVA_HOME = Split-Path (Split-Path $javaExe -Parent) -Parent
[IO.File]::WriteAllText((Join-Path $LocalDir 'java-path.txt'), $javaExe)
$pythonExe = Join-Path $ProjectRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $pythonExe)) {
    & $PythonExecutable -m venv (Join-Path $ProjectRoot '.venv')
    Assert-LastExit 'Python environment creation'
}
& $pythonExe -m pip --version *> $null
if ($LASTEXITCODE -ne 0) { & $pythonExe -m ensurepip --upgrade; Assert-LastExit 'pip initialization' }
& $pythonExe -m pip install --cache-dir (Join-Path $ToolsDir 'pip-cache') -r (Join-Path $ProjectRoot 'model\requirements.txt')
Assert-LastExit 'Python dependency installation'
& $pythonExe (Join-Path $ProjectRoot 'scripts\download_runtime.py')
Assert-LastExit 'Runtime download'
& (Join-Path $ProjectRoot 'scripts\start-db.ps1')
& $pythonExe (Join-Path $ProjectRoot 'scripts\manage_database.py')
Assert-LastExit 'Database setup'
& (Join-Path $ProjectRoot 'build.ps1')
& $pythonExe -u (Join-Path $ProjectRoot 'model\prepare_data.py')
Assert-LastExit 'SOM preparation'
$accounts = "SCX Review - local demo accounts`r`n`r`nadmin : $($env:ADMIN_PASSWORD)`r`nreviewer : $($env:REVIEWER_PASSWORD)`r`n`r`nKeep this file private. Accounts are created when the backend first starts.`r`n"
[IO.File]::WriteAllText((Join-Path $LocalDir 'accounts.txt'), $accounts, [Text.UTF8Encoding]::new($false))
Write-Host 'Setup complete. Run .\start.ps1. Login details: .local\accounts.txt'
