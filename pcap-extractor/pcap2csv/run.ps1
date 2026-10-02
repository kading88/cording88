[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$InputPath,

    [Parameter(Mandatory = $true, Position = 1)]
    [string]$OutputDirectory,

    [switch]$Follow,

    [ValidateRange(1, 86400)]
    [int]$SnapshotSeconds = 5
)

$ErrorActionPreference = 'Stop'
$moduleRoot = $PSScriptRoot
$launcher = Join-Path $moduleRoot 'build\install\pcap2csv\bin\pcap2csv.bat'
$java = (Get-Command java.exe -ErrorAction Stop).Source
$pcap2CsvJre = Split-Path -Parent (Split-Path -Parent $java)
$previousJavaHome = $env:JAVA_HOME

if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    & (Join-Path $moduleRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

try {
    $env:JAVA_HOME = $pcap2CsvJre
    if ($Follow) {
        & $launcher --follow $InputPath $OutputDirectory --snapshot-seconds $SnapshotSeconds
    } else {
        & $launcher $InputPath $OutputDirectory
    }
    $runExitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousJavaHome
}

exit $runExitCode
