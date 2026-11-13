# Paso 9 de la guía: indicadores de rendimiento y disponibilidad.
# Compara lo que reporta MIRS con los contadores de rendimiento de Windows en el mismo momento.
. "$PSScriptRoot\00_comun.ps1"

Paso 'MIRS frente a los contadores de Windows'
$contadores = Get-Counter -Counter '\Processor(_Total)\% Processor Time', '\Memory\% Committed Bytes In Use',
    '\Memory\Available MBytes' -SampleInterval 2 -MaxSamples 1
$estado = Invoke-RestMethod -Uri $UrlApi -TimeoutSec 5
$so = Get-CimInstance Win32_OperatingSystem
$ramWin = 100 * (1 - $so.FreePhysicalMemory / $so.TotalVisibleMemorySize)
$cpuWin = ($contadores.CounterSamples | Where-Object Path -like '*processor time').CookedValue
[pscustomobject]@{ Indicador = 'CPU (%)'; MIRS = $estado.muestra.cpu_pct; Windows = [Math]::Round($cpuWin, 1) },
[pscustomobject]@{ Indicador = 'RAM en uso (%)'; MIRS = $estado.muestra.ram_pct; Windows = [Math]::Round($ramWin, 1) } |
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
