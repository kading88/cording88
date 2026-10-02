param(
    [string]$InputPath = (Join-Path $PSScriptRoot 'examples\sample.pcap'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'output'),
    [string]$MysqlConfig,
    [string]$JavaHome
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build.ps1') -JavaHome $JavaHome
$local = Join-Path $PSScriptRoot '.local'
$jdk = (Get-Content -LiteralPath (Join-Path $local 'java-home.txt') -Raw).Trim()
$java = Join-Path $jdk 'bin\java.exe'
$native = Join-Path $PSScriptRoot 'lib\jnetpcap'
$classpath = "$local\classes;$local\lib\*;$native\jnetpcap.jar"
$arguments = @('-Duser.language=en', '-Duser.country=US', '-Duser.timezone=UTC', '-Dfile.encoding=UTF-8', "-Djava.library.path=$native", '-cp', $classpath, 'PcapToCsv', $InputPath, $OutputDirectory)
if ($MysqlConfig) { $arguments += $MysqlConfig }
& $java @arguments
if ($LASTEXITCODE -ne 0) { throw "PCAP conversion failed (exit $LASTEXITCODE)." }
