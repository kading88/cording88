param([string]$JavaHome)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$local = Join-Path $root '.local'
$javaPathFile = Join-Path $local 'java-home.txt'
New-Item -ItemType Directory -Force -Path $local | Out-Null
if (-not $JavaHome -and (Test-Path -LiteralPath $javaPathFile)) { $JavaHome = (Get-Content -LiteralPath $javaPathFile -Raw).Trim() }
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) { throw 'Pass -JavaHome with the path to a 64-bit JDK 8, or set JAVA_HOME.' }
$java = Join-Path $JavaHome 'bin\java.exe'
$javac = Join-Path $JavaHome 'bin\javac.exe'
if (-not (Test-Path -LiteralPath $javac)) { throw 'A JDK is required, including bin\javac.exe.' }
# JDK 8 writes its version to stderr; Windows PowerShell 5 wraps that as an error record.
$savedPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $version = (& $javac -version 2>&1 | Out-String)
} finally { $ErrorActionPreference = $savedPreference }
if ($version -notmatch 'javac 1\.8\.') { throw 'Use a 64-bit JDK 8 for the bundled jNetPcap native library.' }

$native = Join-Path $root 'lib\jnetpcap'
foreach ($name in @('jnetpcap.jar', 'jnetpcap.dll', 'jnetpcap-pcap100.dll')) {
    if (-not (Test-Path -LiteralPath (Join-Path $native $name))) { throw "Missing native dependency: $name" }
}

$lib = Join-Path $local 'lib'
New-Item -ItemType Directory -Force -Path $lib | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$dependencies = @(
    @('org/slf4j/slf4j-api/1.7.25', 'slf4j-api-1.7.25.jar', '18c4a0095d5c1da6b817592e767bb23d29dd2f560ad74df75ff3961dbde25b79'),
    @('org/slf4j/slf4j-nop/1.7.25', 'slf4j-nop-1.7.25.jar', '6cb127138f41b5a869f9ecdd061ad17799a0e3fe7204600797154eb0432eeb12'),
    @('org/apache/commons/commons-lang3/3.6', 'commons-lang3-3.6.jar', '89c27f03fff18d0b06e7afd7ef25e209766df95b6c1269d6c3ebbdea48d5f284'),
    @('org/apache/commons/commons-math3/3.5', 'commons-math3-3.5.jar', 'dcf9768726b82e8d7b8a928447b08bca7cfe2773d5120a63a41ec224207c1a29'),
    @('org/apache/tika/tika-core/1.17', 'tika-core-1.17.jar', '77c0a63d7693dc968553f92ef061469979b04d458bb11996d7738c45c5f66b5c'),
    @('com/mysql/mysql-connector-j/9.7.0', 'mysql-connector-j-9.7.0.jar', '0353648eaa1c91e0f4020c959abf756bc866ffd583df22ae6b6f6e0cbd43eb44')
)
foreach ($dependency in $dependencies) {
    $target = Join-Path $lib $dependency[1]
    if (-not (Test-Path -LiteralPath $target)) {
        $temporary = "$target.download"
        Invoke-WebRequest -UseBasicParsing -Uri "https://repo.maven.apache.org/maven2/$($dependency[0])/$($dependency[1])" -OutFile $temporary
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $dependency[2]) { throw "Download checksum mismatch: $($dependency[1])" }
        Move-Item -LiteralPath $temporary -Destination $target -Force
    }
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $dependency[2]) { throw "Dependency checksum mismatch: $($dependency[1])" }
}
$classes = Join-Path $local 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src') -File -Filter '*.java' | ForEach-Object FullName)
$classpath = "$lib\*;$native\jnetpcap.jar"
& $javac -encoding UTF-8 -cp $classpath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
[IO.File]::WriteAllText($javaPathFile, $JavaHome, [Text.UTF8Encoding]::new($false))
Write-Host 'Build complete: application and CICFlowMeter sources compiled directly from src.'
