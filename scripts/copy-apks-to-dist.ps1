# =============================================================================
# copy-apks-to-dist.ps1
# Copia las APKs (debug y release) de app/build/outputs/apk/ a dist/ con
# nombres versionados para distribuir.
#
# Usage (desde la raiz del proyecto):
#   powershell -ExecutionPolicy Bypass -File scripts\copy-apks-to-dist.ps1
#
# Resultado en dist/:
#   CSAE-POS_v0.9.13-f55-mobile-harden_debug_2026-08-17.apk       (22 MB)
#   CSAE-POS_v0.9.13-f55-mobile-harden_release_2026-08-17.apk     (3 MB, firmado)
#
# El versionName se lee dinamicamente de app/build.gradle.kts asi no hay
# que actualizar el script en cada bump.
# =============================================================================

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$buildGradle = Join-Path $root 'app\build.gradle.kts'
$apkDebug = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
$apkRelease = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
$distDir = Join-Path $root 'dist'
$date = Get-Date -Format 'yyyy-MM-dd'

# Leer versionName del build.gradle.kts. Match: versionName = "X"
if (-not (Test-Path $buildGradle)) { throw "No encontre $buildGradle" }
$content = Get-Content $buildGradle -Raw
$match = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"')
if (-not $match.Success) { throw "No encontre versionName en $buildGradle" }
$versionName = $match.Groups[1].Value
Write-Host "versionName leido: $versionName" -ForegroundColor Cyan

# Crear dist/ si no existe.
if (-not (Test-Path $distDir)) {
    New-Item -ItemType Directory -Path $distDir -Force | Out-Null
    Write-Host "Creado directorio: $distDir" -ForegroundColor Yellow
}

# Funcion helper para copiar + medir tamano.
function Copy-Apk {
    param(
        [string]$Source,
        [string]$Suffix,
        [string]$Version,
        [string]$Date
    )
    if (-not (Test-Path $Source)) {
        Write-Host "SKIP: $Source no existe" -ForegroundColor Yellow
        return
    }
    $destName = "CSAE-POS_v$Version" + "_$Suffix" + "_$Date.apk"
    $dest = Join-Path $distDir $destName
    Copy-Item $Source $dest -Force
    $sizeMB = [math]::Round((Get-Item $dest).Length / 1MB, 2)
    Write-Host "  -> $destName ($sizeMB MB)" -ForegroundColor Green
}

Write-Host "`nCopiando APKs a dist/..." -ForegroundColor Cyan
Copy-Apk -Source $apkDebug -Suffix 'debug' -Version $versionName -Date $date
Copy-Apk -Source $apkRelease -Suffix 'release' -Version $versionName -Date $date

Write-Host "`nListo. dist/:" -ForegroundColor Cyan
Get-ChildItem $distDir -Filter '*.apk' | Sort-Object LastWriteTime -Descending | Select-Object Name, @{N='SizeMB';E={[math]::Round($_.Length/1MB,2)}}, LastWriteTime | Format-Table -AutoSize
