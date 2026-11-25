# BudGuard build script (bare toolchain, no Gradle / Android Studio).
# Requires: JDK 17 (javac/jar/keytool) + Android SDK build-tools (aapt2/d8/zipalign/apksigner) + android.jar
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1

[CmdletBinding()]
param(
    [string]$Toolchain = ''
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $Toolchain) { $Toolchain = Join-Path (Split-Path $root -Parent) '_toolchain' }

$appDir   = Join-Path $root 'app'
$mainDir  = Join-Path $appDir 'src\main'
$out      = Join-Path $root 'build'
$genDir   = Join-Path $out 'gen'
$classDir = Join-Path $out 'classes'
$dexDir   = Join-Path $out 'dex'

# Clean intermediate outputs first, so deleted sources do not linger in the APK
foreach ($d in @($genDir, $classDir, $dexDir)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Path $d -Force | Out-Null
}
foreach ($f in @('classes.jar', 'unsigned.apk', 'aligned.apk', 'res.zip')) {
    $p = Join-Path $out $f
    if (Test-Path $p) { Remove-Item $p -Force }
}
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Path $out -Force | Out-Null }

function Find-First([string]$dir, [string]$filter) {
    if (-not (Test-Path $dir)) { return $null }
    Get-ChildItem -Path $dir -Recurse -Filter $filter -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
}

Write-Host "[1/8] locating toolchain ..." -ForegroundColor Cyan
$java = Find-First (Join-Path $Toolchain 'jdk') 'java.exe'
if (-not $java) {
    $c = Get-Command java -ErrorAction SilentlyContinue
    if ($c) { $java = $c.Source }
}
if (-not $java) { throw "java.exe not found: put a JDK in $Toolchain\jdk or add java to PATH" }

$jdkBin = Split-Path $java -Parent
$env:JAVA_HOME = Split-Path $jdkBin -Parent
$env:PATH = "$jdkBin;$env:PATH"

$javac   = Join-Path $jdkBin 'javac.exe'
$jar     = Join-Path $jdkBin 'jar.exe'
$keytool = Join-Path $jdkBin 'keytool.exe'

$sdkRoot = Join-Path $Toolchain 'sdk'
if (-not (Test-Path $sdkRoot)) { $sdkRoot = $env:ANDROID_HOME }
if (-not $sdkRoot -or -not (Test-Path $sdkRoot)) { throw "Android SDK not found: put it in $Toolchain\sdk or set ANDROID_HOME" }

$aapt2     = Find-First (Join-Path $sdkRoot 'build-tools') 'aapt2.exe'
$d8        = Find-First (Join-Path $sdkRoot 'build-tools') 'd8.bat'
$zipalign  = Find-First (Join-Path $sdkRoot 'build-tools') 'zipalign.exe'
$apksigner = Find-First (Join-Path $sdkRoot 'build-tools') 'apksigner.bat'

$platform = Get-ChildItem (Join-Path $sdkRoot 'platforms') -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $platform) { throw "no platforms under $sdkRoot\platforms (need android-33+)" }
$androidJar = Join-Path $platform.FullName 'android.jar'

foreach ($t in @($aapt2, $d8, $zipalign, $apksigner)) {
    if (-not $t) { throw "missing build-tools component (aapt2/d8/zipalign/apksigner)" }
}
Write-Host "  java    = $java"
Write-Host "  aapt2   = $aapt2"
Write-Host "  android = $androidJar"

Write-Host "[2/8] aapt2 compile ..." -ForegroundColor Cyan
$resZip = Join-Path $out 'res.zip'
& $aapt2 compile --dir (Join-Path $mainDir 'res') -o $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[3/8] aapt2 link ..." -ForegroundColor Cyan
$unsigned = Join-Path $out 'unsigned.apk'
& $aapt2 link -o $unsigned `
    --manifest (Join-Path $mainDir 'AndroidManifest.xml') `
    -I $androidJar `
    --java $genDir `
    --min-sdk-version 26 `
    --target-sdk-version 33 `
    --version-code 1 `
    --version-name 1.0 `
    --auto-add-overlay `
    $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[4/8] javac ..." -ForegroundColor Cyan
$sources = @(Get-ChildItem (Join-Path $mainDir 'java') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$rJava = Join-Path $genDir 'com\budguard\R.java'
if (-not (Test-Path $rJava)) { throw "R.java was not generated: $rJava" }
& $javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options `
    -classpath $androidJar -d $classDir $sources $rJava
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[5/8] jar + d8 ..." -ForegroundColor Cyan
$classesJar = Join-Path $out 'classes.jar'
& $jar cf $classesJar -C $classDir .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
# JDK 17 removed java.ext.dirs, so d8.bat (build-tools 33) breaks; call the jar directly.
$btDir = Split-Path $d8 -Parent
$d8Jar = Join-Path $btDir 'lib\d8.jar'
$apksignerJar = Join-Path $btDir 'lib\apksigner.jar'
& $java -cp $d8Jar com.android.tools.r8.D8 --release --min-api 26 --lib $androidJar --output $dexDir $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[6/8] injecting classes.dex ..." -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$dexPath = Join-Path $dexDir 'classes.dex'
if (-not (Test-Path $dexPath)) { throw "d8 produced no classes.dex" }
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
$entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
$es = $entry.Open()
$fs = [System.IO.File]::OpenRead($dexPath)
$fs.CopyTo($es)
$fs.Dispose(); $es.Dispose(); $zip.Dispose()

Write-Host "[7/8] zipalign + sign ..." -ForegroundColor Cyan
$aligned = Join-Path $out 'aligned.apk'
& $zipalign -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$ks = Join-Path $root 'budguard.keystore'
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -keystore $ks -storepass budguard -keypass budguard `
        -alias budguard -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=BudGuard, OU=Dev, O=Local, L=Local, ST=Local, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$final = Join-Path $out 'BudGuard-debug.apk'
& $java -jar $apksignerJar sign --ks $ks --ks-pass pass:budguard --key-pass pass:budguard `
    --ks-key-alias budguard --out $final $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

Write-Host "[8/8] verify ..." -ForegroundColor Cyan
& $java -jar $apksignerJar verify --print-certs $final
if ($LASTEXITCODE -ne 0) { throw "signature verify failed" }

$size = [math]::Round((Get-Item $final).Length / 1KB, 1)
Write-Host ""
Write-Host "BUILD OK: $final ($size KB)" -ForegroundColor Green
Write-Host "install : adb install -r `"$final`"" -ForegroundColor Green
