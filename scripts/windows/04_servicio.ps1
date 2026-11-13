# Paso 6 de la guía: el servidor de MIRS como proceso en segundo plano que arranca con Windows.
# Se usa una tarea programada porque MIRS_Servidor.exe es una aplicación de consola y no
# implementa la interfaz de servicio de Windows (SCM). La tarea corre como LOCAL SERVICE,
# arranca al iniciar el equipo y se reinicia sola si el proceso termina con error.
. "$PSScriptRoot\00_comun.ps1"
Requiere-Admin

Paso 'Registro de la tarea programada'
$accion = New-ScheduledTaskAction -Execute $RutaExe -Argument "--lan --bd `"$RutaBD`"" -WorkingDirectory $Dir.Bin
$inicio = New-ScheduledTaskTrigger -AtStartup
$cuenta = New-ScheduledTaskPrincipal -UserId 'NT AUTHORITY\LOCALSERVICE' -LogonType ServiceAccount -RunLevel Limited
$ajustes = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew -StartWhenAvailable
Register-ScheduledTask -TaskName $Cfg.NombreTarea -Action $accion -Trigger $inicio -Principal $cuenta `
    -Settings $ajustes -Description 'Servidor de MIRS: monitoreo y API para la app móvil' -Force | Out-Null
Start-ScheduledTask -TaskName $Cfg.NombreTarea

$estado = Esperar-Api 60
if (-not $estado) { throw 'La API no respondió después de iniciar la tarea.' }

Get-ScheduledTask -TaskName $Cfg.NombreTarea |
    Select-Object TaskName, State, @{n='Cuenta'; e={$_.Principal.UserId}}, @{n='Disparador'; e={'Al iniciar el equipo'}} |
    Format-Table -AutoSize

Paso 'Proceso en ejecución'
Get-CimInstance Win32_Process -Filter "Name = 'MIRS_Servidor.exe'" | ForEach-Object {
    $dueño = Invoke-CimMethod -InputObject $_ -MethodName GetOwner
    $p = Get-Process -Id $_.ProcessId
    [pscustomobject]@{
        PID       = $_.ProcessId
        Usuario   = "$($dueño.Domain)\$($dueño.User)"
        Prioridad = $p.PriorityClass
        Hilos     = $p.Threads.Count
        MemoriaMB = [Math]::Round($p.WorkingSet64 / 1MB, 1)
        Inicio    = $p.StartTime
    }
} | Format-Table -AutoSize

Paso 'Puertos abiertos por el servidor'
$pids = (Get-Process -Name 'MIRS_Servidor').Id
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object OwningProcess -in $pids |
    Select-Object @{n='Protocolo'; e={'TCP'}}, LocalAddress, LocalPort, OwningProcess | Format-Table -AutoSize
Get-NetUDPEndpoint -ErrorAction SilentlyContinue | Where-Object OwningProcess -in $pids |
    Select-Object @{n='Protocolo'; e={'UDP'}}, LocalAddress, LocalPort, OwningProcess | Format-Table -AutoSize

Paso 'Respuesta de la API'
Write-Host "Equipo: $($estado.equipo.nombre_host) | $($estado.equipo.sistema_operativo) $($estado.equipo.version_so)"
Write-Host "Muestra $($estado.muestra.fecha_hora): CPU $($estado.muestra.cpu_pct) %  RAM $($estado.muestra.ram_pct) %  Disco $($estado.muestra.disco_pct) %"
