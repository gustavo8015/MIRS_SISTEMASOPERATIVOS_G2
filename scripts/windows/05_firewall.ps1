# Paso 7 de la guía: reglas de firewall para la API (TCP 8765) y la búsqueda de equipos (UDP 8766).
# Solo se abren en perfiles Privado y Dominio y solo para la subred local; en redes públicas quedan cerradas.
. "$PSScriptRoot\00_comun.ps1"
Requiere-Admin

Paso 'Perfiles del firewall'
Set-NetFirewallProfile -Profile Domain, Private, Public -Enabled True
Get-NetFirewallProfile | Format-Table Name, Enabled, DefaultInboundAction, DefaultOutboundAction -AutoSize

Paso 'Reglas de entrada de MIRS'
Get-NetFirewallRule -Group 'MIRS' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
$comunes = @{ Group = 'MIRS'; Direction = 'Inbound'; Action = 'Allow'; Profile = 'Domain,Private'
              RemoteAddress = 'LocalSubnet'; Program = $RutaExe }
New-NetFirewallRule @comunes -DisplayName 'MIRS - API (TCP 8765)' -Protocol TCP -LocalPort $Cfg.PuertoApi | Out-Null
New-NetFirewallRule @comunes -DisplayName 'MIRS - Buscar equipos (UDP 8766)' -Protocol UDP -LocalPort $Cfg.PuertoBusqueda | Out-Null
New-NetFirewallRule -Group 'MIRS' -DisplayName 'MIRS - Bloqueo en red pública' -Direction Inbound -Action Block `
    -Profile Public -Protocol TCP -LocalPort $Cfg.PuertoApi | Out-Null

Get-NetFirewallRule -Group 'MIRS' | ForEach-Object {
    $puerto = $_ | Get-NetFirewallPortFilter
    $dir    = $_ | Get-NetFirewallAddressFilter
    [pscustomobject]@{
        Regla     = $_.DisplayName
        Accion    = $_.Action
        Perfil    = $_.Profile
        Protocolo = $puerto.Protocol
        Puerto    = $puerto.LocalPort
        Origen    = ($dir.RemoteAddress -join ',')
        Activa    = $_.Enabled
    }
} | Format-Table -AutoSize
