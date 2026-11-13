# Paso 9 de la guía: indicadores de rendimiento y disponibilidad.
# Compara lo que reporta MIRS con los contadores de rendimiento de Windows en el mismo momento.
. "$PSScriptRoot\00_comun.ps1"

Paso 'MIRS frente a los contadores de Windows (promedio de los mismos 20 segundos)'
# Las dos fuentes se leen durante la misma ventana y se comparan sus promedios.
$inicio = Get-Date
$contadores = Get-Counter -Counter '\Processor(_Total)\% Processor Time', '\Memory\Available MBytes' -SampleInterval 2 -MaxSamples 10
$totalMB = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1MB
$cpuWin = ($contadores.CounterSamples | Where-Object Path -like '*processor time' | Measure-Object CookedValue -Average).Average
$libreMB = ($contadores.CounterSamples | Where-Object Path -like '*available mbytes' | Measure-Object CookedValue -Average).Average
# (en Windows PowerShell 5.1 un arreglo JSON llega como un solo objeto: se guarda antes de filtrarlo)
$ultimas = Invoke-RestMethod -Uri "http://127.0.0.1:$($Cfg.PuertoApi)/api/muestras?cantidad=30" -TimeoutSec 5
$ventana = $ultimas | Where-Object { [datetime]$_.fecha_hora -ge $inicio }
$estado = Invoke-RestMethod -Uri $UrlApi -TimeoutSec 5
[pscustomobject]@{ Indicador = 'CPU (%)'; MIRS = [Math]::Round(($ventana | Measure-Object cpu_pct -Average).Average, 1)
                   Windows = [Math]::Round($cpuWin, 1); Muestras = "$(@($ventana).Count) / 10" },
[pscustomobject]@{ Indicador = 'RAM en uso (%)'; MIRS = [Math]::Round(($ventana | Measure-Object ram_pct -Average).Average, 1)
                   Windows = [Math]::Round(100 * (1 - $libreMB / $totalMB), 1); Muestras = "$(@($ventana).Count) / 10" } |
    Format-Table -AutoSize
Write-Host "Núcleos lógicos reportados por MIRS: $(@($estado.nucleos).Count)"

Paso 'Procesos de mayor consumo según MIRS'
$estado.procesos | Select-Object pid, nombre, estado, prioridad, cpu_pct, memoria_mb, hilos | Format-Table -AutoSize

Paso 'Disco y red'
Get-Volume -DriveLetter C | Select-Object DriveLetter,
    @{n='UsoPct'; e={[Math]::Round(100 * (1 - $_.SizeRemaining / $_.Size), 1)}} | Format-Table -AutoSize
Write-Host "MIRS: disco $($estado.muestra.disco_pct) %, envío $($estado.muestra.red_envio_kbps) kB/s, recepción $($estado.muestra.red_recepcion_kbps) kB/s"

Paso 'Disponibilidad del servicio'
$t = Measure-Command { $null = Invoke-RestMethod -Uri $UrlApi -TimeoutSec 5 }
$tcp = Test-NetConnection -ComputerName 127.0.0.1 -Port $Cfg.PuertoApi -WarningAction SilentlyContinue
$muestras = Invoke-RestMethod -Uri "http://127.0.0.1:$($Cfg.PuertoApi)/api/muestras?cantidad=1000" -TimeoutSec 5
[pscustomobject]@{
    PuertoTCPAbierto   = $tcp.TcpTestSucceeded
    TiempoRespuestaMs  = [Math]::Round($t.TotalMilliseconds)
    MuestrasGuardadas  = @($muestras).Count
    PrimeraMuestra     = $muestras[0].fecha_hora
    UltimaMuestra      = $muestras[-1].fecha_hora
    AlertasActivas     = $estado.alertas_activas
} | Format-List
