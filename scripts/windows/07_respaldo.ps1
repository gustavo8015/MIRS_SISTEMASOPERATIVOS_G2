# Paso 5 de la guía (almacenamiento): respaldo en caliente de la base de datos con retención.
# Uso:  .\07_respaldo.ps1            (hace un respaldo ahora)
#       .\07_respaldo.ps1 -Registrar (además lo programa todos los días)
param([switch]$Registrar)
. "$PSScriptRoot\00_comun.ps1"

Paso 'Respaldo de la base de datos'
$destino = Join-Path $Dir.Respaldos ('mirs_' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '.db')
$json = & $RutaExe --bd $RutaBD --respaldo $destino --sin-bluetooth
if ($LASTEXITCODE -ne 0) { throw "El respaldo falló (código $LASTEXITCODE)." }
$r = $json | Select-Object -Last 1 | ConvertFrom-Json
Write-Host "Archivo: $($r.destino)"
Write-Host "Integridad (PRAGMA integrity_check): $($r.integridad)"
$r.filas.PSObject.Properties | Select-Object @{n='Tabla'; e={$_.Name}}, @{n='Filas'; e={$_.Value}} | Format-Table -AutoSize
Escribir-Log 'respaldo.log' "Respaldo $destino integridad=$($r.integridad)"

Paso "Retención: se conservan los $($Cfg.DiasRetencion) respaldos más recientes"
Get-ChildItem $Dir.Respaldos -Filter 'mirs_*.db' | Sort-Object LastWriteTime -Descending |
    Select-Object -Skip $Cfg.DiasRetencion | Remove-Item -Verbose
Get-ChildItem $Dir.Respaldos -Filter 'mirs_*.db' |
    Select-Object Name, @{n='TamañoKB'; e={[Math]::Round($_.Length / 1KB)}}, LastWriteTime | Format-Table -AutoSize

if ($Registrar) {
    Requiere-Admin
    Paso 'Tarea programada de respaldo diario'
    $accion = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    $disparo = New-ScheduledTaskTrigger -Daily -At $Cfg.HoraRespaldo
    Register-ScheduledTask -TaskName $Cfg.TareaRespaldo -Action $accion -Trigger $disparo -User 'SYSTEM' `
        -RunLevel Highest -Description 'Respaldo diario de la base de datos de MIRS' -Force | Out-Null
    Get-ScheduledTask -TaskName $Cfg.TareaRespaldo | Get-ScheduledTaskInfo |
        Select-Object TaskName, NextRunTime | Format-Table -AutoSize
}
