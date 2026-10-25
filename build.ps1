# Compila esta version de la app usando el JDK compartido de ..\.toolchain
# Uso:   .\build.ps1            -> APK release firmado
#        .\build.ps1 -Debug     -> APK debug (para pruebas)
param([switch]$Debug)

$ErrorActionPreference = "Stop"

$toolchain = Join-Path (Split-Path $PSScriptRoot -Parent) ".toolchain"
$jdk = Get-ChildItem -Path $toolchain -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue |
    Select-Object -First 1

if (-not $jdk) {
    throw "No se encontro el JDK en '$toolchain'. Revisa el README de la carpeta padre."
}

$env:JAVA_HOME = $jdk.FullName
Write-Host "JDK: $($jdk.Name)" -ForegroundColor DarkGray

$task = if ($Debug) { "assembleDebug" } else { "assembleRelease" }

# Gradle escribe avisos por stderr; no deben tratarse como error de PowerShell.
$ErrorActionPreference = "Continue"
$output = & (Join-Path $PSScriptRoot "gradlew.bat") $task "-p" $PSScriptRoot --console=plain 2>&1
$code = $LASTEXITCODE
$output | ForEach-Object { Write-Host $_.ToString() }
$ErrorActionPreference = "Stop"

if ($code -ne 0) { throw "La compilacion fallo (codigo $code)" }

$relative = if ($Debug) {
    "app\build\outputs\apk\debug\app-debug.apk"
} else {
    "app\build\outputs\apk\release\app-release.apk"
}

$apk = Join-Path $PSScriptRoot $relative
if (-not (Test-Path $apk)) { throw "No se genero el APK esperado: $relative" }

$dist = Join-Path $PSScriptRoot "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$suffix = if ($Debug) { "debug" } else { "release" }
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$target = Join-Path $dist "RadiosDelPeru-$stamp-$suffix.apk"
Copy-Item $apk $target -Force

$mb = [math]::Round((Get-Item $target).Length / 1MB, 2)
Write-Host ""
Write-Host "APK listo: $target ($mb MB)" -ForegroundColor Green
