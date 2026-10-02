. (Join-Path $PSScriptRoot 'scripts\common.ps1')
Read-ProjectEnv
$jarPath = Join-Path $ProjectRoot 'backend\target\scx-review-1.0.0.jar'
$process = Get-ProjectProcess (Join-Path $LocalDir 'backend.pid') $jarPath
if ($process) {
    $backendProcess = Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue
    if ($backendProcess) {
        Stop-Process -InputObject $backendProcess
        if (-not $backendProcess.WaitForExit(30000)) { throw 'Backend did not stop within 30 seconds.' }
    }
    Write-Host 'Project backend stopped.'
} else {
    Write-Host 'No matching project backend process was found.'
}
$mysqlConfig = Join-Path $LocalDir 'mysql.ini'
$mysqlProcessInfo = Get-ProjectProcess (Join-Path $LocalDir 'mysql.pid') $mysqlConfig
if ($mysqlProcessInfo) {
    $mysqlProcess = Get-Process -Id $mysqlProcessInfo.ProcessId -ErrorAction SilentlyContinue
    & (Join-Path $ProjectRoot '.venv\Scripts\python.exe') (Join-Path $ProjectRoot 'scripts\manage_database.py') --shutdown
    Assert-LastExit 'MySQL shutdown'
    # Wait for shutdown cleanup to finish before allowing an immediate restart.
    if ($mysqlProcess -and -not $mysqlProcess.WaitForExit(30000)) { throw 'MySQL did not stop within 30 seconds.' }
} else {
    Write-Host 'No matching project MySQL process was found.'
}

foreach ($port in @([int]$env:APP_PORT, [int]$env:DB_PORT)) {
    $client = [Net.Sockets.TcpClient]::new()
    $stillListening = $false
    try {
        $client.Connect('127.0.0.1', $port)
        $stillListening = $true
    } catch [Net.Sockets.SocketException] {
    } finally {
        $client.Dispose()
    }
    if ($stillListening) {
        throw "Port $port is still listening. The process could not be identified as this project; no unrelated process was stopped."
    }
}
Write-Host "Project stopped. Web port $($env:APP_PORT) and MySQL port $($env:DB_PORT) are closed."
