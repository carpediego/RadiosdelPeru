# Publica una version de la app:
#   1. compila el APK release (build.ps1)
#   2. calcula su sha256
#   3. escribe version.json (lo que lee el actualizador de la app)
#
# Uso:
#   .\tools\release.ps1 -Notes "Arreglo del audio en segundo plano"
#   .\tools\release.ps1 -NoBuild          # si el APK ya esta compilado
#
# Despues hay que crear el release en GitHub con el APK que queda en dist\
# y subir version.json (el script imprime los pasos).
param(
    [string]$Notes = "",
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent
$gradleFile = Join-Path $root "app\build.gradle.kts"
$REPO = "carpediego/RadiosdelPeru"

if (-not (Test-Path $gradleFile)) { throw "No encuentro $gradleFile" }

$gradle = Get-Content $gradleFile -Raw
$versionName = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $versionName -or -not $versionCode) {
    throw "No pude leer versionName/versionCode de app\build.gradle.kts"
}

Write-Host "Version a publicar: $versionName (versionCode $versionCode)" -ForegroundColor Cyan

if (-not $NoBuild) {
    & (Join-Path $root "build.ps1")
}

$dist = Join-Path $root "dist"
$apk = Get-ChildItem $dist -Filter "RadiosDelPeru-*-release.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "No hay APK release en dist. Corre .\build.ps1 primero." }

# Nombre estable: es el que se sube al release y el que espera version.json
$assetName = "RadiosDelPeru-$versionName.apk"
$asset = Join-Path $dist $assetName
Copy-Item $apk.FullName $asset -Force

$hash = (Get-FileHash $asset -Algorithm SHA256).Hash.ToLower()
$tag = "v$versionName"
$url = "https://github.com/$REPO/releases/download/$tag/$assetName"

$json = [ordered]@{
    schema      = 1
    versionCode = [int]$versionCode
    versionName = $versionName
    apkUrl      = $url
    sha256      = $hash
    notes       = $Notes
    publishedAt = (Get-Date -Format "yyyy-MM-dd")
}

$jsonPath = Join-Path $root "version.json"
$json | ConvertTo-Json | Set-Content -Path $jsonPath -Encoding utf8

# Limpieza: deja solo el APK con nombre estable (los demas se regeneran compilando).
Get-ChildItem $dist -Filter "RadiosDelPeru-*-release.apk" | Where-Object { $_.FullName -ne $asset } | ForEach-Object {
    if ($_.FullName -like "$dist\*") { Remove-Item $_.FullName -Force }
}

Write-Host ""
Write-Host "version.json:" -ForegroundColor Green
Get-Content $jsonPath | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
Write-Host ""
Write-Host "APK para subir: $asset" -ForegroundColor Green
Write-Host ("  sha256: " + $hash)
Write-Host ""
Write-Host "Siguiente paso: crear el release '$tag' y subir ese APK:" -ForegroundColor Yellow
Write-Host "  https://github.com/$REPO/releases/new?tag=$tag&title=Radios%20del%20Per%C3%BA%20$versionName"
Write-Host ""
Write-Host "Y publicar el version.json:" -ForegroundColor Yellow
Write-Host "  git add version.json; git commit -m ""version $versionName""; git push"
