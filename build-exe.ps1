# ============================================================
# GmScript Build Script (No Maven/WiX required)
# Output: target/dist/GmScript/GmScript.exe (bundled JRE)
# ============================================================

$ErrorActionPreference = "Stop"
$ProjectDir = $PSScriptRoot
$JavaHome = "$env:USERPROFILE\.jdks\corretto-17.0.20"
$M2 = "$env:USERPROFILE\.m2\repository"

$javac = "$JavaHome\bin\javac.exe"
$jar = "$JavaHome\bin\jar.exe"
$jpackage = "$JavaHome\bin\jpackage.exe"

$classesDir = "$ProjectDir\target\classes"
$libsDir = "$ProjectDir\target\libs"
$distDir = "$ProjectDir\target\dist"

Write-Host "===== [1/5] Clean =====" -ForegroundColor Cyan
if (Test-Path $classesDir) { Remove-Item $classesDir -Recurse -Force }
if (Test-Path $libsDir) { Remove-Item $libsDir -Recurse -Force }
if (Test-Path $distDir) { Remove-Item $distDir -Recurse -Force }
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
New-Item -ItemType Directory -Path $libsDir -Force | Out-Null

Write-Host "===== [2/5] Compile =====" -ForegroundColor Cyan
$depJars = @(
    "$M2\com\formdev\flatlaf\3.4\flatlaf-3.4.jar",
    "$M2\org\luaj\luaj-jse\3.0.1\luaj-jse-3.0.1.jar",
    "$M2\com\github\kwhat\jnativehook\2.2.2\jnativehook-2.2.2.jar",
    "$M2\com\squareup\okhttp3\okhttp\4.12.0\okhttp-4.12.0.jar",
    "$M2\com\squareup\okio\okio-jvm\3.6.0\okio-jvm-3.6.0.jar",
    "$M2\org\jetbrains\kotlin\kotlin-stdlib\1.9.10\kotlin-stdlib-1.9.10.jar",
    "$M2\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar",
    "$M2\net\java\dev\jna\jna\5.14.0\jna-5.14.0.jar",
    "$M2\net\java\dev\jna\jna-platform\5.14.0\jna-platform-5.14.0.jar"
)

foreach ($j in $depJars) {
    if (-not (Test-Path $j)) {
        Write-Host "[ERROR] Missing: $j" -ForegroundColor Red
        exit 1
    }
}

$cp = ($depJars -join ";")
$sources = Get-ChildItem "$ProjectDir\src\main\java" -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName

$compileOutput = & $javac -cp $cp -d $classesDir -encoding UTF-8 --release 17 $sources 2>&1
if ($LASTEXITCODE -ne 0) {
    $compileOutput | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    exit 1
}
Write-Host "  OK - $($sources.Count) files compiled" -ForegroundColor Green

Write-Host "===== [3/5] Copy resources =====" -ForegroundColor Cyan
$resDir = "$ProjectDir\src\main\resources"
if (Test-Path $resDir) {
    Copy-Item "$resDir\*" $classesDir -Recurse -Force
    Write-Host "  OK" -ForegroundColor Green
}

Write-Host "===== [4/5] Package JARs =====" -ForegroundColor Cyan
$manifestFile = "$ProjectDir\target\MANIFEST.MF"
[System.IO.File]::WriteAllText($manifestFile, "Main-Class: com.gmscpt.App`r`n", [System.Text.Encoding]::ASCII)

& $jar --create --file "$libsDir\gmscpt-1.0.0.jar" --manifest $manifestFile -C $classesDir .
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] jar failed" -ForegroundColor Red
    exit 1
}
Write-Host "  gmscpt-1.0.0.jar created" -ForegroundColor Green

foreach ($j in $depJars) {
    Copy-Item $j $libsDir
}
Write-Host "  $($depJars.Count) dependency JARs copied" -ForegroundColor Green

Write-Host "===== [5/5] jpackage -> EXE =====" -ForegroundColor Cyan
$appName = "GmScript"
$appVersion = "1.0.0"

$jpArgs = @(
    "--type", "app-image",
    "--name", $appName,
    "--app-version", $appVersion,
    "--vendor", "GmScript",
    "--description", "Game macro script tool with AI assistant",
    "--input", $libsDir,
    "--main-jar", "gmscpt-1.0.0.jar",
    "--main-class", "com.gmscpt.App",
    "--dest", $distDir,
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Xmx512m"
)

$iconFile = "$ProjectDir\src\main\resources\app.ico"
if (Test-Path $iconFile) {
    $jpArgs += "--icon"
    $jpArgs += $iconFile
}

& $jpackage @jpArgs 2>&1 | ForEach-Object { Write-Host $_ }

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] jpackage failed!" -ForegroundColor Red
    exit 1
}

$exePath = "$distDir\$appName\$appName.exe"
if (Test-Path $exePath) {
    $size = [math]::Round((Get-ChildItem "$distDir\$appName" -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
    Write-Host ""
    Write-Host "============================================" -ForegroundColor Green
    Write-Host "  BUILD SUCCESS!" -ForegroundColor Green
    Write-Host "  EXE: $exePath" -ForegroundColor Green
    Write-Host "  Size: ${size} MB (with JRE)" -ForegroundColor Green
    Write-Host "  Distribute: zip the entire '$appName' folder" -ForegroundColor Green
    Write-Host "============================================" -ForegroundColor Green
} else {
    Write-Host "[ERROR] EXE not found: $exePath" -ForegroundColor Red
    exit 1
}
