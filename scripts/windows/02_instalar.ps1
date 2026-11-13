# Paso 4 de la guía: instalación de MIRS en C:\MIRS.
# Uso:  .\02_instalar.ps1 -Origen .\MIRS_Servidor.exe  [-NuevoNombre PC-MIRS-01]
param(
    [Parameter(Mandatory)] [string]$Origen,
    [string]$NuevoNombre
)
. "$PSScriptRoot\00_comun.ps1"
Requiere-Admin

Paso 'Estructura de carpetas'
foreach ($d in $Dir.Values) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
Copy-Item -Path $Origen -Destination $RutaExe -Force
Get-ChildItem $Cfg.Raiz -Recurse | Select-Object FullName,
    @{n='TamañoKB'; e={ if ($_.PSIsContainer) { '' } else { [Math]::Round($_.Length / 1KB) } }} | Format-Table -AutoSize

Paso 'Integridad del ejecutable (SHA-256)'
Get-FileHash $RutaExe -Algorithm SHA256 | Format-List Algorithm, Hash, Path

Paso 'Nombre del equipo'
if ($NuevoNombre -and $NuevoNombre -ne $env:COMPUTERNAME) {
    Rename-Computer -NewName $NuevoNombre -Force
    Write-Host "El equipo se llamará $NuevoNombre después de reiniciar."
} else {
    Write-Host "Nombre actual: $env:COMPUTERNAME (sin cambios)"
}
