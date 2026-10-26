# ============================================================
# GmScript 2.x build script
#
#   Output 1: target\gmscpt-2.0.0-SNAPSHOT.jar (Spring Boot fat jar; run with java -jar)
#   Output 2: target\dist\GmScript\GmScript.exe (jpackage app-image, bundled runtime)
#
# Differences from the 1.x script (which broke on any other machine):
#   * no hardcoded ~/.jdks path and no hand-listed m2 jars
#   * uses Maven repackage output instead of hand-rolled javac + classpath
#   * passes --win-console: the 1.x exe had no console, so hook failures were invisible
# ============================================================

$ErrorActionPreference = "Stop"
# NOTE: ASCII-only on purpose. PowerShell 5.1 parses BOM-less UTF-8 as the local
#       ANSI codepage and turns CJK text into parser errors. Keep it ASCII.
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # some hosts block encoding changes; harmless for the build
}
$ProjectDir = $PSScriptRoot
$AppVersion = "2.0.0"
$AppName = "GmScript"

function Find-Jdk21 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += @(
        "$env:USERPROFILE\.jdks\corretto-21*",
        "$env:USERPROFILE\.jdks\temurin-21*",
        "$env:USERPROFILE\.jdks\zulu-21*",
        "C:\Program Files\Java\jdk-21*",
        "C:\Program Files\Eclipse Adoptium\jdk-21*",
        "E:\IntelliJ IDEA 2025.3\jbr"
    )
    foreach ($c in $candidates) {
        foreach ($expanded in (Get-Item $c -ErrorAction SilentlyContinue)) {
            $bin = Join-Path $expanded.FullName "bin"
            if (Test-Path (Join-Path $bin "java.exe")) { return $expanded.FullName }
        }
    }
    return $null
}

Write-Host "===== [1/4] Locate JDK 21 =====" -ForegroundColor Cyan
# JAVA_HOME must be resolved BEFORE invoking Maven: otherwise Maven fails with only
# a bare "The JAVA_HOME environment variable is not defined correctly" (hit exactly this).
$jdk = Find-Jdk21
if (-not $jdk) {
    Write-Host "[ERROR] No JDK 21 found. Install one (e.g. Amazon Corretto 21) or set JAVA_HOME." -ForegroundColor Red
    Write-Host "        Without a JDK neither the frontend build nor Maven packaging works." -ForegroundColor Red
    exit 1
}
$env:JAVA_HOME = $jdk
Write-Host "  JAVA_HOME = $jdk" -ForegroundColor Green

Write-Host "===== [2/4] Build frontend (Vite) =====" -ForegroundColor Cyan
$webDir = Join-Path $ProjectDir "web"
if (Test-Path (Join-Path $webDir "package.json")) {
    Push-Location $webDir
    if (-not (Test-Path (Join-Path $webDir "node_modules"))) {
        Write-Host "  First build: installing dependencies..." -ForegroundColor Yellow
        npm install
        if ($LASTEXITCODE -ne 0) { Pop-Location; Write-Host "[ERROR] npm install failed" -ForegroundColor Red; exit 1 }
    }
    npm run build
    $webOk = ($LASTEXITCODE -eq 0)
    Pop-Location
    if (-not $webOk) { Write-Host "[ERROR] frontend build failed" -ForegroundColor Red; exit 1 }
    Write-Host "  frontend output -> src\main\resources\static" -ForegroundColor Green
} else {
    Write-Host "  skipped: no web/ directory" -ForegroundColor Yellow
}

Write-Host "===== [3/4] Maven package (fat jar) =====" -ForegroundColor Cyan
Push-Location $ProjectDir
$mvn = Join-Path $ProjectDir "mvnw.cmd"
if (Test-Path $mvn) {
    & $mvn -B -DskipTests package
} else {
    # no wrapper yet: fall back to the Maven shipped with IntelliJ IDEA
    $ideaMvn = "E:\IntelliJ IDEA 2025.3\plugins\maven\lib\maven3\bin\mvn.cmd"
    if (Test-Path $ideaMvn) {
        & $ideaMvn -B -DskipTests package
    } else {
        & mvn -B -DskipTests package
    }
}
$mvnOk = ($LASTEXITCODE -eq 0)
Pop-Location
if (-not $mvnOk) { Write-Host "[ERROR] Maven packaging failed" -ForegroundColor Red; exit 1 }

$fatJar = Get-ChildItem "$ProjectDir\target\gmscpt-*.jar" |
    Where-Object { $_.Name -notlike "*original*" -and $_.Name -notlike "*sources*" } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $fatJar) { Write-Host "[ERROR] fat jar not found" -ForegroundColor Red; exit 1 }
Write-Host "  $($fatJar.Name)" -ForegroundColor Green

Write-Host "===== [4/4] jpackage -> EXE =====" -ForegroundColor Cyan
$jpackage = Join-Path $jdk "bin\jpackage.exe"
if (-not (Test-Path $jpackage)) {
    Write-Host "  This JDK has no jpackage - skipping EXE packaging" -ForegroundColor Yellow
    Write-Host "  The fat jar is ready; run it with:" -ForegroundColor Yellow
    Write-Host "    java -jar `"$($fatJar.FullName)`"" -ForegroundColor Yellow
    Write-Host "  For an EXE: point JAVA_HOME at a full JDK 21 that ships jpackage" -ForegroundColor Yellow
    Write-Host "  (Amazon Corretto 21 / Temurin 21). IntelliJ JBR is runtime-only." -ForegroundColor Yellow
    exit 0
}
Write-Host "  using $jdk" -ForegroundColor Green

$distDir = "$ProjectDir\target\dist"
if (Test-Path $distDir) { Remove-Item $distDir -Recurse -Force }
$stageDir = "$ProjectDir\target\jpackage-input"
if (Test-Path $stageDir) { Remove-Item $stageDir -Recurse -Force }
New-Item -ItemType Directory -Path $stageDir -Force | Out-Null
Copy-Item $fatJar.FullName $stageDir

$jpArgs = @(
    "--type", "app-image",
    "--name", $AppName,
    "--app-version", $AppVersion,
    "--vendor", "GmScript",
    "--description", "Local AI macro scripting workbench (Spring Boot + browser UI)",
    "--input", $stageDir,
    "--main-jar", $fatJar.Name,
    "--dest", $distDir,
    "--win-console",                       # without it the user never sees hook failures
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Xmx1g"
)
$iconFile = "$ProjectDir\src\main\resources\app.ico"
if (Test-Path $iconFile) { $jpArgs += @("--icon", $iconFile) }

& $jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] jpackage failed" -ForegroundColor Red; exit 1 }

$exePath = "$distDir\$AppName\$AppName.exe"
if (Test-Path $exePath) {
    $size = [math]::Round((Get-ChildItem "$distDir\$AppName" -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
    Write-Host ""
    Write-Host "============================================" -ForegroundColor Green
    Write-Host "  BUILD SUCCESS" -ForegroundColor Green
    Write-Host "  EXE : $exePath (${size} MB incl. runtime)" -ForegroundColor Green
    Write-Host "  JAR : $($fatJar.FullName)" -ForegroundColor Green
    Write-Host "============================================" -ForegroundColor Green
} else {
    Write-Host "[ERROR] exe not produced: $exePath" -ForegroundColor Red
    exit 1
}
