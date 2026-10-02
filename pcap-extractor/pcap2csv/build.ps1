[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$moduleRoot = $PSScriptRoot
$repositoryRoot = Split-Path -Parent $moduleRoot
$javac = (Get-Command javac.exe -ErrorAction Stop).Source
$pcap2CsvJdk = Split-Path -Parent (Split-Path -Parent $javac)
$previousJavaHome = $env:JAVA_HOME
$previousGradleUserHome = $env:GRADLE_USER_HOME
$pcap2CsvGradleUserHome = Join-Path $repositoryRoot '.gradle-user-home'
$gradleSearchPath = Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-4.2-*\*\gradle-4.2\bin\gradle.bat'
$installedGradle = Get-ChildItem -Path $gradleSearchPath -File -ErrorAction SilentlyContinue |
    Select-Object -First 1
$gradle = if ($null -ne $installedGradle) {
    $installedGradle.FullName
} else {
    Join-Path $repositoryRoot 'gradlew.bat'
}

try {
    $env:JAVA_HOME = $pcap2CsvJdk
    $env:GRADLE_USER_HOME = $pcap2CsvGradleUserHome
    & $gradle -p $moduleRoot clean verifyExtraction --no-daemon
    $buildExitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:GRADLE_USER_HOME = $previousGradleUserHome
}

exit $buildExitCode
