# Paso 2 y 3 de la guía: sistema operativo y recursos del equipo (solo lectura).
. "$PSScriptRoot\00_comun.ps1"

Paso 'Sistema operativo'
$so = Get-CimInstance Win32_OperatingSystem
[pscustomobject]@{
    Equipo        = $env:COMPUTERNAME
    Sistema       = $so.Caption
    Version       = $so.Version
    Compilacion   = $so.BuildNumber
    Arquitectura  = $so.OSArchitecture
    Instalado     = $so.InstallDate
    UltimoInicio  = $so.LastBootUpTime
} | Format-List

Paso 'Procesador y memoria'
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$cs  = Get-CimInstance Win32_ComputerSystem
[pscustomobject]@{
    Procesador       = $cpu.Name.Trim()
    NucleosFisicos   = $cpu.NumberOfCores
    NucleosLogicos   = $cpu.NumberOfLogicalProcessors
    MemoriaTotalGB   = [Math]::Round($cs.TotalPhysicalMemory / 1GB, 1)
    MemoriaLibreGB   = [Math]::Round($so.FreePhysicalMemory / 1MB, 1)
    Virtualizado     = $cs.Model
} | Format-List

Paso 'Almacenamiento (discos y volúmenes)'
Get-Disk | Select-Object Number, FriendlyName, PartitionStyle,
    @{n='TamañoGB'; e={[Math]::Round($_.Size / 1GB, 1)}} | Format-Table -AutoSize
Get-Volume | Where-Object DriveLetter | Select-Object DriveLetter, FileSystem,
    @{n='TamañoGB'; e={[Math]::Round($_.Size / 1GB, 1)}},
    @{n='LibreGB';  e={[Math]::Round($_.SizeRemaining / 1GB, 1)}}, HealthStatus | Format-Table -AutoSize

Paso 'Red'
Get-NetIPConfiguration | Where-Object IPv4Address | ForEach-Object {
    [pscustomobject]@{
        Interfaz = $_.InterfaceAlias
        IPv4     = ($_.IPv4Address.IPAddress -join ', ')
        Puerta   = ($_.IPv4DefaultGateway.NextHop -join ', ')
        DNS      = ($_.DNSServer | Where-Object AddressFamily -eq 2 | ForEach-Object ServerAddresses) -join ', '
    }
} | Format-Table -AutoSize
