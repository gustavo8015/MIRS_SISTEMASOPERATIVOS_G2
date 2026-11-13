# Paso 10 de la guía: pruebas de funcionamiento. Cada prueba imprime OK o FALLA y el script
# termina con código 1 si alguna falla.
param([switch]$Carga)
. "$PSScriptRoot\00_comun.ps1"
$ErrorActionPreference = 'Continue'
$resultados = New-Object System.Collections.Generic.List[object]

function Probar([string]$id, [string]$nombre, [scriptblock]$prueba) {
    try { $detalle = & $prueba; $ok = $true } catch { $detalle = $_.Exception.Message; $ok = $false }
    $resultados.Add([pscustomobject]@{ ID = $id; Prueba = $nombre; Resultado = $(if ($ok) { 'OK' } else { 'FALLA' }); Detalle = "$detalle" })
}
function Afirmar($condicion, [string]$mensaje) { if (-not $condicion) { throw $mensaje } }

Probar 'P01' 'Sistema operativo compatible (Windows 10 o posterior)' {
    $v = [Environment]::OSVersion.Version; Afirmar ($v.Major -ge 10) "versión $v"; (Get-CimInstance Win32_OperatingSystem).Caption }
Probar 'P02' 'Usuarios y grupos de MIRS creados' {
    $m = Get-LocalGroupMember -Group $Cfg.GrupoOperadores | Where-Object Name -like "*\$($Cfg.UsuarioOperador)"
    Afirmar $m 'mirs_operador no está en MIRS-Operadores'; "mirs_operador en $($Cfg.GrupoOperadores)" }
Probar 'P03' 'Operadores sin permiso de escritura en datos' {
    $sid = (Get-LocalGroup -Name $Cfg.GrupoOperadores).SID
    $acl = (Get-Acl $Dir.Datos).GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]) |
        Where-Object IdentityReference -eq $sid
    Afirmar ($acl.FileSystemRights -notmatch 'Write|Modify|FullControl') "derechos: $($acl.FileSystemRights)"
    "derechos: $($acl.FileSystemRights)" }
Probar 'P04' 'Grupo Users sin acceso a C:\MIRS' {
    $acl = (Get-Acl $Cfg.Raiz).GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]) |
        Where-Object { $_.IdentityReference.Value -eq 'S-1-5-32-545' }
    Afirmar (-not $acl) 'Users conserva permisos heredados'; 'herencia cortada' }
Probar 'P05' 'Tarea del servidor registrada como LOCAL SERVICE' {
    $t = Get-ScheduledTask -TaskName $Cfg.NombreTarea
    Afirmar ($t.Principal.UserId -match 'LOCAL ?SERVICE') $t.Principal.UserId; "estado $($t.State)" }
Probar 'P06' 'API responde con una muestra válida' {
    $e = Invoke-RestMethod -Uri $UrlApi -TimeoutSec 5
    Afirmar ($e.muestra.cpu_pct -ge 0 -and $e.muestra.cpu_pct -le 100) 'CPU fuera de rango'
    "CPU $($e.muestra.cpu_pct) %, RAM $($e.muestra.ram_pct) %" }
Probar 'P07' 'Ruta inexistente devuelve 404' {
    try { Invoke-RestMethod "http://127.0.0.1:$($Cfg.PuertoApi)/api/no-existe" -TimeoutSec 5; throw 'respondió 200' }
    catch { Afirmar ($_.Exception.Response.StatusCode.value__ -eq 404) $_.Exception.Message; 'HTTP 404' } }
Probar 'P08' 'Búsqueda de equipos por UDP 8766' {
    $udp = New-Object System.Net.Sockets.UdpClient; $udp.Client.ReceiveTimeout = 3000
    $bytes = [Text.Encoding]::ASCII.GetBytes('MIRS_BUSCAR'); [void]$udp.Send($bytes, $bytes.Length, '127.0.0.1', $Cfg.PuertoBusqueda)
    $remoto = New-Object System.Net.IPEndPoint([Net.IPAddress]::Any, 0)
    $r = [Text.Encoding]::UTF8.GetString($udp.Receive([ref]$remoto)) | ConvertFrom-Json; $udp.Close()
    Afirmar ($r.servicio -eq 'MIRS') 'respuesta inesperada'; "$($r.nombre_host) puerto $($r.puerto)" }
Probar 'P09' 'Reglas de firewall activas y limitadas a la subred local' {
    $r = Get-NetFirewallRule -Group 'MIRS' | Where-Object Action -eq 'Allow'
    Afirmar (@($r).Count -eq 2) "reglas: $(@($r).Count)"
    Afirmar ((($r | Get-NetFirewallAddressFilter).RemoteAddress | Sort-Object -Unique) -eq 'LocalSubnet') 'origen abierto'
    '2 reglas Allow (Privado/Dominio), origen LocalSubnet' }
Probar 'P10' 'Política de contraseñas aplicada' {
    $l = ((net accounts) -match '(length|longitud)' | Select-Object -First 1).Split(':')[-1].Trim()
    Afirmar ([int]$l -ge $Cfg.LongitudMinima) "longitud mínima $l"; "longitud mínima $l" }
Probar 'P11' 'Respaldo existente e íntegro' {
    $ultimo = Get-ChildItem $Dir.Respaldos -Filter 'mirs_*.db' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    Afirmar $ultimo 'no hay respaldos'
    Afirmar ((Get-Content (Join-Path $Dir.Logs 'respaldo.log') -Tail 1) -match 'integridad=ok') 'integridad no verificada'
    "$($ultimo.Name) ($([Math]::Round($ultimo.Length / 1KB)) KB)" }
Probar 'P12' 'Recuperación ante fallo: proceso terminado y restablecido por el vigilante' {
    $antes = (Get-Process -Name 'MIRS_Servidor').Id
    Stop-Process -Id $antes -Force; Start-Sleep -Seconds 2
    Afirmar (-not (Get-Process -Name 'MIRS_Servidor' -ErrorAction SilentlyContinue)) 'el proceso seguía activo'
    & "$PSScriptRoot\09_vigilante.ps1" | Out-Null
    $despues = (Get-Process -Name 'MIRS_Servidor' -ErrorAction Stop).Id
    Afirmar (Esperar-Api 30) 'la API no volvió'; "PID $antes terminado, nuevo PID $despues" }
if ($Carga) {
    Probar 'P13' 'Carga de CPU sostenida genera alerta' {
        $previas = @(Invoke-RestMethod "http://127.0.0.1:$($Cfg.PuertoApi)/api/alertas" -TimeoutSec 5).Count
        $n = [Environment]::ProcessorCount
        $trabajos = 1..$n | ForEach-Object { Start-Job { $f = (Get-Date).AddSeconds(90); while ((Get-Date) -lt $f) { $null = 1 } } }
        $alerta = $null; $limite = (Get-Date).AddSeconds(100)
        while (-not $alerta -and (Get-Date) -lt $limite) {
            Start-Sleep -Seconds 5
            $alerta = Invoke-RestMethod "http://127.0.0.1:$($Cfg.PuertoApi)/api/alertas" -TimeoutSec 5 |
                Where-Object tipo -eq 'PICO_CPU' | Select-Object -First 1
        }
        $trabajos | Stop-Job -PassThru | Remove-Job
        Afirmar $alerta "sin alerta de CPU (alertas previas: $previas)"
        "$($alerta.severidad): $($alerta.mensaje)" }
}

$resultados | Format-Table ID, Resultado, Prueba, Detalle -AutoSize -Wrap
$fallas = @($resultados | Where-Object Resultado -eq 'FALLA').Count
Write-Host "Pruebas: $($resultados.Count)  OK: $($resultados.Count - $fallas)  FALLA: $fallas"
if ($fallas) { exit 1 }
