. (Join-Path $PSScriptRoot 'scripts\common.ps1')
Read-ProjectEnv
& (Join-Path $ProjectRoot 'scripts\start-db.ps1')
$jarPath = Join-Path $ProjectRoot 'backend\target\scx-review-1.0.0.jar'
$pidPath = Join-Path $LocalDir 'backend.pid'
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Build output is missing; run setup.ps1 or build.ps1.' }
if (Get-ProjectProcess $pidPath $jarPath) { Write-Host "Already running: http://127.0.0.1:$($env:APP_PORT)"; return }
if (Get-NetTCPConnection -State Listen -LocalPort ([int]$env:APP_PORT) -ErrorAction SilentlyContinue) {
    throw "Port $($env:APP_PORT) is already used by another process. Change APP_PORT in .env."
}
$javaExe = Get-ProjectJava
$process = Start-Process -FilePath $javaExe -ArgumentList @('-Duser.timezone=UTC', '-Dfile.encoding=UTF-8', '-jar', "`"$jarPath`"") -WorkingDirectory $ProjectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $LocalDir 'backend.log') -RedirectStandardError (Join-Path $LocalDir 'backend-error.log')
[IO.File]::WriteAllText($pidPath, $process.Id.ToString())
Wait-ProjectPort ([int]$env:APP_PORT)
Write-Host "SCX Review: http://127.0.0.1:$($env:APP_PORT)"
Write-Host 'Accounts: .local\accounts.txt | Stop: .\stop.ps1'
