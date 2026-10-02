. (Join-Path $PSScriptRoot 'common.ps1')
Read-ProjectEnv
$mysqlBase = Join-Path $ToolsDir 'mysql'
$mysqlExe = Join-Path $mysqlBase 'bin\mysqld.exe'
$dataDir = Join-Path $LocalDir 'mysql-data'
$configPath = Join-Path $LocalDir 'mysql.ini'
$pidPath = Join-Path $LocalDir 'mysql.pid'
if (-not (Test-Path -LiteralPath $mysqlExe)) { throw 'MySQL runtime is missing; run setup.ps1.' }
if (Get-ProjectProcess $pidPath $configPath) { Write-Host 'Project MySQL is already running.'; return }
$existingListener = Get-NetTCPConnection -State Listen -LocalPort ([int]$env:DB_PORT) -ErrorAction SilentlyContinue
if ($existingListener) { throw "Port $($env:DB_PORT) is already used by another process. Change DB_PORT in .env." }
if (-not (Test-Path -LiteralPath (Join-Path $dataDir 'auto.cnf'))) {
    New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
    & $mysqlExe --no-defaults --initialize-insecure "--basedir=$mysqlBase" "--datadir=$dataDir"
    Assert-LastExit 'MySQL initialization'
}
$settings = @"
[mysqld]
basedir="$($mysqlBase.Replace('\','/'))"
datadir="$($dataDir.Replace('\','/'))"
port=$($env:DB_PORT)
bind-address=127.0.0.1
mysqlx=0
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
default-time-zone=+00:00
pid-file="$($pidPath.Replace('\','/'))"
log-error="$((Join-Path $LocalDir 'mysql-error.log').Replace('\','/'))"
"@
[IO.File]::WriteAllText($configPath, $settings, [Text.UTF8Encoding]::new($false))
$process = Start-Process -FilePath $mysqlExe -ArgumentList @("--defaults-file=`"$configPath`"") -WindowStyle Hidden -PassThru
Wait-ProjectPort ([int]$env:DB_PORT)
Write-Host "Project MySQL is running on 127.0.0.1:$($env:DB_PORT)."
