# abrir-android-studio.ps1
# Configura el proyecto para que Android Studio no muestre los popups de Trust y JVM
# y lo abre con todo listo.
#
# Uso: powershell -ExecutionPolicy Bypass -File .\abrir-android-studio.ps1

$ErrorActionPreference = 'Continue'
$ProjectPath = 'C:\Users\claud\.minimax\projects\csae-pos-android'
$AndroidStudioPath = 'C:\Program Files\Android\Android Studio\bin\studio64.exe'
$Jdk21Path = 'C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot'
$AndroidSdkPath = Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk'

Write-Host "==> 1) Cerrar Android Studio si esta abierto" -ForegroundColor Cyan
$running = Get-Process -Name 'studio64' -ErrorAction SilentlyContinue
if ($running) {
    Write-Host "   Cerrando $($running.Count) instancia(s)..."
    $running | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 3
} else {
    Write-Host "   No hay instancias abiertas."
}

Write-Host "==> 2) Verificar JDK 21" -ForegroundColor Cyan
$javaExe = Join-Path $Jdk21Path 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    Write-Host "   [ERROR] No encontre java.exe en $javaExe" -ForegroundColor Red
    exit 1
}
Write-Host "   OK: $javaExe existe"

Write-Host "==> 3) Marcar proyecto como trusted" -ForegroundColor Cyan
$ideaDir = Join-Path $ProjectPath '.idea'
if (-not (Test-Path $ideaDir)) {
    New-Item -ItemType Directory -Path $ideaDir | Out-Null
}
$trustedFile = Join-Path $ideaDir '.trusted'
Set-Content -Path $trustedFile -Value '# trusted' -Encoding UTF8
$workspaceXml = Join-Path $ideaDir 'workspace.xml'
$workspaceContent = '<?xml version="1.0" encoding="UTF-8"?><project version="4"><component name="PropertiesComponent"><property name="TrustedProject" value="true" /></component><component name="TrustedProjectsManager"><option name="TRUSTED_PROJECT_STATE" value="true" /></component></project>'
Set-Content -Path $workspaceXml -Value $workspaceContent -Encoding UTF8
Write-Host "   OK: .idea/.trusted + .idea/workspace.xml"

Write-Host "==> 4) Crear local.properties" -ForegroundColor Cyan
$localProps = Join-Path $ProjectPath 'local.properties'
$sdkEscaped = $AndroidSdkPath.Replace('\', '\\')
$sdkLine = "sdk.dir=$sdkEscaped"
$needsWrite = $true
if (Test-Path $localProps) {
    $content = Get-Content $localProps -Raw
    if ($content -match 'sdk\.dir=') { $needsWrite = $false }
}
if ($needsWrite) {
    Set-Content -Path $localProps -Value $sdkLine -Encoding UTF8
    Write-Host "   OK: $sdkLine"
} else {
    Write-Host "   local.properties ya tiene sdk.dir"
}

Write-Host "==> 5) Pre-sincronizar Gradle desde CLI (descarga deps)" -ForegroundColor Cyan
$env:JAVA_HOME = $Jdk21Path
$env:Path = "$Jdk21Path\bin;$env:Path"
$gradlewPath = Join-Path $ProjectPath 'gradlew.bat'
$proc = New-Object System.Diagnostics.Process
$proc.StartInfo.FileName = $gradlewPath
$proc.StartInfo.Arguments = 'help --no-daemon --console=plain'
$proc.StartInfo.RedirectStandardOutput = $true
$proc.StartInfo.RedirectStandardError = $true
$proc.StartInfo.UseShellExecute = $false
$proc.StartInfo.WorkingDirectory = $ProjectPath
$proc.StartInfo.CreateNoWindow = $true
$null = $proc.Start()
$gradleOut = $proc.StandardOutput.ReadToEnd() + $proc.StandardError.ReadToEnd()
$proc.WaitForExit()
$gradleExit = $proc.ExitCode
if ($gradleExit -ne 0) {
    Write-Host "   [WARN] gradle help fallo (exit $gradleExit), seguimos igual" -ForegroundColor Yellow
    $gradleOut.Split("`n") | Select-Object -Last 5 | ForEach-Object { Write-Host "   $_" -ForegroundColor Yellow }
} else {
    Write-Host "   OK: gradle respondio"
}

Write-Host "==> 6) Registrar JDK 21 de Microsoft en jdk.table.xml" -ForegroundColor Cyan
$studioConfigDirs = Get-ChildItem -Path (Join-Path $env:APPDATA 'Google') -Filter 'AndroidStudio*' -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
if ($studioConfigDirs) {
    foreach ($dir in $studioConfigDirs) {
        $jdkTablePath = Join-Path $dir.FullName 'options\jdk.table.xml'
        if (Test-Path $jdkTablePath) {
            $jdkXml = Get-Content $jdkTablePath -Raw
            if ($jdkXml -notmatch 'Microsoft JDK 21') {
                $jdkPathEscaped = $Jdk21Path.Replace('\', '\\')
                $jdkEntry = "  <jdk version=`"2`"><name value=`"Microsoft JDK 21`" /><type value=`"JavaSDK`" /><version value=`"21.0.12`" /><homePath value=`"$jdkPathEscaped`" /></jdk>`n  </component>"
                $jdkXml = $jdkXml -replace '  </component>', $jdkEntry
                Set-Content -Path $jdkTablePath -Value $jdkXml -Encoding UTF8
                Write-Host "   OK: registrado en $($dir.Name)"
            } else {
                Write-Host "   $($dir.Name) ya tiene Microsoft JDK 21"
            }
        }
    }
} else {
    Write-Host "   [WARN] no encontre config dirs de Android Studio" -ForegroundColor Yellow
}

Write-Host "==> 7) Abrir Android Studio con el proyecto" -ForegroundColor Cyan
if (Test-Path $AndroidStudioPath) {
    Start-Process -FilePath $AndroidStudioPath -ArgumentList "`"$ProjectPath`""
    Start-Sleep -Seconds 3
    $procCount = (Get-Process -Name 'studio64' -ErrorAction SilentlyContinue).Count
    Write-Host "   Studio64 procesos: $procCount"
    Write-Host ""
    Write-Host "==> Listo! Android Studio abierto." -ForegroundColor Green
    Write-Host "   - Proyecto: $ProjectPath" -ForegroundColor Green
    Write-Host "   - JDK 21: $Jdk21Path" -ForegroundColor Green
    Write-Host "   - Trust: marcado" -ForegroundColor Green
    Write-Host "   - SDK: $AndroidSdkPath" -ForegroundColor Green
    Write-Host ""
    Write-Host "Si aparece algun popup, dale aceptar/trust/yes." -ForegroundColor Yellow
} else {
    Write-Host "   [ERROR] Android Studio no encontrado en $AndroidStudioPath" -ForegroundColor Red
}
