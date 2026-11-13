# Paso 8 y 10 de la guía: recuperación automática ante fallos.
# Si la API no responde, reinicia la tarea del servidor y deja constancia en C:\MIRS\logs\vigilante.log.
# Uso:  .\09_vigilante.ps1             (una revisión)
#       .\09_vigilante.ps1 -Registrar  (revisión cada 5 minutos con una tarea programada)
param([switch]$Registrar)
. "$PSScriptRoot\00_comun.ps1"

try {
    $null = Invoke-RestMethod -Uri $UrlApi -TimeoutSec 5
    Escribir-Log 'vigilante.log' 'API disponible'
} catch {
    Escribir-Log 'vigilante.log' "API sin respuesta ($($_.Exception.Message)); se reinicia $($Cfg.NombreTarea)"
    Stop-ScheduledTask -TaskName $Cfg.NombreTarea -ErrorAction SilentlyContinue
    Get-Process -Name 'MIRS_Servidor' -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-ScheduledTask -TaskName $Cfg.NombreTarea
    if (Esperar-Api 60) { Escribir-Log 'vigilante.log' 'Servidor recuperado' }
    else { Escribir-Log 'vigilante.log' 'ERROR: el servidor no se recuperó'; exit 1 }
}

if ($Registrar) {
    Requiere-Admin
    $accion = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    $disparo = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 5)
    Register-ScheduledTask -TaskName $Cfg.TareaVigilante -Action $accion -Trigger $disparo -User 'SYSTEM' `
        -RunLevel Highest -Description 'Reinicia el servidor de MIRS si la API deja de responder' -Force | Out-Null
    Get-ScheduledTask -TaskName $Cfg.TareaVigilante | Format-Table TaskName, State -AutoSize
}
