# Paso 7 y 10 de la guía: comprobación de la auditoría y del bloqueo de cuentas.
# Intenta iniciar sesión cinco veces con una contraseña errónea para mirs_operador, verifica que la
# cuenta quede bloqueada y muestra los eventos que registró el registro de Seguridad de Windows.
param([switch]$ProbarBloqueo)
. "$PSScriptRoot\00_comun.ps1"
Requiere-Admin

if ($ProbarBloqueo) {
    Paso "Intentos de inicio de sesión fallidos con $($Cfg.UsuarioOperador)"
    $mala = ConvertTo-SecureString 'ClaveIncorrecta-123' -AsPlainText -Force
    $cred = New-Object System.Management.Automation.PSCredential($Cfg.UsuarioOperador, $mala)
    for ($i = 1; $i -le $Cfg.IntentosBloqueo; $i++) {
        try {
            Start-Process cmd.exe -ArgumentList '/c', 'exit' -Credential $cred -WorkingDirectory $env:SystemRoot -Wait -WindowStyle Hidden
            Write-Host "Intento ${i}: aceptado (no esperado)"
        } catch { Write-Host "Intento ${i}: rechazado ($($_.Exception.Message.Trim()))" }
    }
    $cuenta = Get-CimInstance Win32_UserAccount -Filter "LocalAccount = True AND Name = '$($Cfg.UsuarioOperador)'"
    Write-Host "Cuenta bloqueada: $($cuenta.Lockout)"
    if ($cuenta.Lockout) {
        Set-CimInstance -InputObject $cuenta -Property @{ Lockout = $false }
        Write-Host 'La cuenta se desbloqueó manualmente para continuar.'
    }
}

Start-Sleep -Seconds 5   # el registro de Seguridad escribe los eventos con un pequeño retraso
Paso 'Eventos de seguridad registrados (últimos 30 minutos)'
$ids = @{ 4720 = 'Cuenta creada'; 4732 = 'Miembro agregado a grupo local'; 4625 = 'Inicio de sesión fallido'
          4740 = 'Cuenta bloqueada'; 4767 = 'Cuenta desbloqueada'; 4688 = 'Proceso creado (MIRS_Servidor)'
          4656 = 'Acceso a C:\MIRS\datos denegado'; 4670 = 'Permisos de un objeto cambiados' }
$desde = (Get-Date).AddMinutes(-30)
$eventos = Get-WinEvent -FilterHashtable @{ LogName = 'Security'; Id = [int[]]$ids.Keys; StartTime = $desde } -ErrorAction SilentlyContinue |
    Where-Object { $_.Id -ne 4688 -or $_.Message -match 'MIRS_Servidor' }
$eventos | Group-Object Id | ForEach-Object {
    [pscustomobject]@{ IdEvento = $_.Name; Descripcion = $ids[[int]$_.Name]; Cantidad = $_.Count
                       Ultimo = ($_.Group | Sort-Object TimeCreated | Select-Object -Last 1).TimeCreated }
} | Sort-Object IdEvento | Format-Table -AutoSize

Paso 'Detalle del bloqueo y de los intentos fallidos'
Get-WinEvent -FilterHashtable @{ LogName = 'Security'; Id = 4625, 4740, 4767 } -MaxEvents 8 -ErrorAction SilentlyContinue |
    Sort-Object TimeCreated | ForEach-Object {
    $x = [xml]$_.ToXml(); $d = @{}; $x.Event.EventData.Data | ForEach-Object { $d[$_.Name] = $_.'#text' }
    [pscustomobject]@{ Hora = $_.TimeCreated; Id = $_.Id; Cuenta = $d['TargetUserName']; Estado = $d['Status'] }
} | Format-Table -AutoSize
