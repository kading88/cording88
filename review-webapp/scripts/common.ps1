$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path $PSScriptRoot -Parent
$LocalDir = Join-Path $ProjectRoot '.local'
$ToolsDir = Join-Path $ProjectRoot '.tools'
New-Item -ItemType Directory -Force -Path $LocalDir | Out-Null

function Read-ProjectEnv {
    $envFile = Join-Path $ProjectRoot '.env'
    if (-not (Test-Path -LiteralPath $envFile)) { throw 'Run setup.ps1 first to create .env.' }
    foreach ($line in [IO.File]::ReadAllLines($envFile)) {
        if ($line.Trim() -and -not $line.TrimStart().StartsWith('#')) {
            $parts = $line.Split('=', 2)
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
        }
    }
}

function Get-ProjectJava {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    $saved = Join-Path $LocalDir 'java-path.txt'
    if (Test-Path -LiteralPath $saved) { $candidates += [IO.File]::ReadAllText($saved).Trim() }
    $available = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($available) { $candidates += $available.Source }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not (Test-Path -LiteralPath $candidate)) { continue }
        # Java writes its version to stderr; read the streams directly so Windows PowerShell
        # does not treat the version text as a terminating NativeCommandError.
        $versionInfo = [Diagnostics.ProcessStartInfo]::new()
        $versionInfo.FileName = $candidate
        $versionInfo.Arguments = '-version'
        $versionInfo.UseShellExecute = $false
        $versionInfo.CreateNoWindow = $true
        $versionInfo.RedirectStandardOutput = $true
        $versionInfo.RedirectStandardError = $true
        $versionProcess = [Diagnostics.Process]::Start($versionInfo)
        try {
            $versionText = $versionProcess.StandardError.ReadToEnd() + $versionProcess.StandardOutput.ReadToEnd()
            $versionProcess.WaitForExit()
        } finally {
            $versionProcess.Dispose()
        }
        if ($versionText -match 'version "(\d+)\.') {
            if ([int]$Matches[1] -ge 21) { return $candidate }
        }
    }
    throw 'JDK 21 or newer is required. Set JAVA_HOME to your JDK directory.'
}

function Get-ProjectProcess([string]$PidPath, [string]$Marker) {
    if (-not (Test-Path -LiteralPath $PidPath)) { return $null }
    $processNumber = 0
    if (-not [int]::TryParse([IO.File]::ReadAllText($PidPath).Trim(), [ref]$processNumber)) { return $null }
    # Fail if process inspection is unavailable to avoid starting a duplicate instance.
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $processNumber" -ErrorAction Stop
    if ($process -and $process.CommandLine -and $process.CommandLine.Contains($Marker)) { return $process }
    return $null
}

function Wait-ProjectPort([int]$Port, [int]$Seconds = 45) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $client = [Net.Sockets.TcpClient]::new()
        try { $client.Connect('127.0.0.1', $Port); return } catch { Start-Sleep -Milliseconds 500 } finally { $client.Dispose() }
    }
    throw "Port $Port did not become available. Check .local logs."
}

function Assert-LastExit([string]$Operation) {
    if ($LASTEXITCODE -ne 0) { throw "$Operation failed (exit $LASTEXITCODE)." }
}
