# auto-trust-studio.ps1
# Auto-click popups de Android Studio. Solo busca dentro de la ventana de Studio.
param(
    [int]$MaxIterations = 60,
    [int]$SleepMs = 2000
)

$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$logFile = Join-Path $PSScriptRoot 'auto-trust.log'
"" | Set-Content -Path $logFile
function L($msg) {
    $ts = Get-Date -Format 'HH:mm:ss'
    $line = "[$ts] $msg"
    Write-Host $line
    Add-Content -Path $logFile -Value $line
}

L "Auto-trust v3 iniciando. PID=$PID, MaxIter=$MaxIterations"

$TreeDescendants = [System.Windows.Automation.TreeScope]::Descendants
$TreeChildren = [System.Windows.Automation.TreeScope]::Children
$InvokePattern = [System.Windows.Automation.InvokePattern]::Pattern
$NameProperty = [System.Windows.Automation.AutomationElement]::NameProperty
$ControlTypeProperty = [System.Windows.Automation.AutomationElement]::ControlTypeProperty
$ProcessIdProperty = [System.Windows.Automation.AutomationElement]::ProcessIdProperty
$ButtonControl = [System.Windows.Automation.ControlType]::Button
$WindowControl = [System.Windows.Automation.ControlType]::Window
$DialogControl = [System.Windows.Automation.ControlType]::Window

$clicked = 0

for ($i = 0; $i -lt $MaxIterations; $i++) {
    Start-Sleep -Milliseconds $SleepMs

    # Buscar la ventana principal de Android Studio por nombre de proceso
    # No filtrar por MainWindowTitle porque durante el splash no tiene titulo
    $studioProc = Get-Process -Name 'studio64' -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 -or $_.MainWindowTitle } |
        Sort-Object StartTime -Descending | Select-Object -First 1
    if (-not $studioProc) {
        L "[$i] Android Studio no esta corriendo. Saliendo."
        break
    }

    # Buscar la ventana de Studio por ProcessId
    $procCondition = New-Object System.Windows.Automation.PropertyCondition($ProcessIdProperty, $studioProc.Id)
    $studioWindow = [System.Windows.Automation.AutomationElement]::RootElement.FindFirst(
        $TreeChildren, $procCondition)
    if (-not $studioWindow) {
        if ($i -in 0, 3, 8) { L "[$i] PID=$($studioProc.Id) pero UIA no encuentra la ventana aun (splash?)" }
        continue
    }

    # Buscar todos los botones dentro de la ventana de Studio
    $btnCondition = New-Object System.Windows.Automation.PropertyCondition($ControlTypeProperty, $ButtonControl)
    $allButtons = $studioWindow.FindAll($TreeDescendants, $btnCondition)

    $found = $false
    foreach ($b in $allButtons) {
        try {
            $name = $b.Current.Name
        } catch { continue }
        if ($name -match '^(Trust Project|Trust|Confiar|Permitir|OK|Stay in Safe Mode)$') {
            try {
                $pattern = $b.GetCurrentPattern($InvokePattern)
                $pattern.Invoke()
                $clicked++
                L "[$i] Click en '$name' (#$clicked)"
                $found = $true
                Start-Sleep -Milliseconds 1000
                break
            } catch {
                L "[$i] Error clickeando '$name': $_"
            }
        }
    }

    # Diagnostico en iteraciones 0, 5, 10
    if ($i -in 0, 3, 8, 15, 25, 40) {
        L "[$i] Botones en ventana Studio: $($allButtons.Count)"
        $count = 0
        foreach ($b in $allButtons) {
            $count++
            if ($count -gt 15) { break }
            try {
                $name = $b.Current.Name
                if ($name -and $name -notmatch '^\s*$') {
                    L "    [$count] '$name'"
                }
            } catch {}
        }
    }

    if (-not $found -and $i -gt 5) {
        L "[$i] Sin popups despues de $i iteraciones. Saliendo."
        break
    }
}

L ""
L "Total clicks: $clicked"
L "Log guardado en $logFile"
