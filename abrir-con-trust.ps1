# abrir-con-trust.ps1
# 1. Cierra Android Studio
# 2. Borra caches locales (fuerza re-sync limpio)
# 3. Abre Android Studio con el proyecto
# 4. En paralelo, auto-click el popup "Trust Project" cuando aparezca
param(
    [string]$ProjectPath = 'C:\Users\claud\.minimax\projects\csae-pos-android',
    [string]$AndroidStudioPath = 'C:\Program Files\Android\Android Studio\bin\studio64.exe'
)

$ErrorActionPreference = 'Continue'

Write-Host "==> 1) Cerrar Android Studio" -ForegroundColor Cyan
Get-Process -Name 'studio64' -ErrorAction SilentlyContinue | ForEach-Object {
    Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 3
Write-Host "   OK"

Write-Host "==> 2) Marcar como trusted (defense in depth)" -ForegroundColor Cyan
$ideaDir = Join-Path $ProjectPath '.idea'
if (-not (Test-Path $ideaDir)) { New-Item -ItemType Directory -Path $ideaDir | Out-Null }
Set-Content -Path (Join-Path $ideaDir '.trusted') -Value '# trusted' -Encoding UTF8
$ws = '<?xml version="1.0" encoding="UTF-8"?><project version="4"><component name="PropertiesComponent"><property name="TrustedProject" value="true" /></component></project>'
Set-Content -Path (Join-Path $ideaDir 'workspace.xml') -Value $ws -Encoding UTF8
Write-Host "   OK"

Write-Host "==> 3) Borrar caches locales del proyecto (.gradle, build)" -ForegroundColor Cyan
$dirsToRemove = @('.gradle', 'build', 'app\build', 'app\.gradle')
foreach ($d in $dirsToRemove) {
    $full = Join-Path $ProjectPath $d
    if (Test-Path $full) {
        try {
            Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction SilentlyContinue
            Write-Host "   Borrado: $d"
        } catch {
            Write-Host "   No se pudo borrar $d (archivos en uso, normal): $_"
        }
    }
}
Write-Host "   OK"

Write-Host "==> 4) Abrir Android Studio con el proyecto" -ForegroundColor Cyan
Start-Process -FilePath $AndroidStudioPath -ArgumentList "`"$ProjectPath`""
Write-Host "   Studio abierto. Esperando a que cargue..."

Write-Host "==> 5) Lanzar auto-trust en background" -ForegroundColor Cyan
$logPath = Join-Path $ProjectPath 'auto-trust.log'
$errPath = Join-Path $ProjectPath 'auto-trust.err'
if (Test-Path $logPath) { Remove-Item $logPath -Force -ErrorAction SilentlyContinue }
if (Test-Path $errPath) { Remove-Item $errPath -Force -ErrorAction SilentlyContinue }

# Lanzar auto-trust
$autoTrustScript = Join-Path $ProjectPath 'auto-trust-studio.ps1'
$proc = Start-Process -FilePath 'powershell.exe' -ArgumentList @(
    '-ExecutionPolicy', 'Bypass',
    '-NoProfile',
    '-File', $autoTrustScript,
    '-MaxIterations', '40',
    '-SleepMs', '1000'
) -RedirectStandardOutput $logPath -RedirectStandardError $errPath -WindowStyle Hidden -PassThru
Write-Host "   Auto-trust PID: $($proc.Id)"
Write-Host "   Esperando 30s para que aparezcan popups..."

Start-Sleep -Seconds 30
Write-Host "--- log de auto-trust ---"
if (Test-Path $logPath) { Get-Content $logPath }
Write-Host "--- end ---"
