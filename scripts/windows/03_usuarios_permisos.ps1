# Paso 4, 5 y 7 de la guía: usuarios, grupos y permisos NTFS con mínimo privilegio.
#  - MIRS-Administradores: control total de C:\MIRS.
#  - MIRS-Operadores: leen datos, respaldos y registros; no pueden modificar la base ni el programa.
#  - LOCAL SERVICE: cuenta con la que corre el servidor; solo escribe en datos y logs.
param([switch]$ProbarAcceso)
. "$PSScriptRoot\00_comun.ps1"
Requiere-Admin
Add-Type -AssemblyName System.Web

function Clave-Aleatoria { [System.Web.Security.Membership]::GeneratePassword(16, 3) }

Paso 'Grupos locales'
foreach ($g in @(
    @{Nombre = $Cfg.GrupoAdmins;     Desc = 'Administran la instalación de MIRS'},
    @{Nombre = $Cfg.GrupoOperadores; Desc = 'Consultan datos, respaldos y registros de MIRS'})) {
    if (-not (Get-LocalGroup -Name $g.Nombre -ErrorAction SilentlyContinue)) {
        New-LocalGroup -Name $g.Nombre -Description $g.Desc | Out-Null
    }
}
Get-LocalGroup -Name 'MIRS-*' | Format-Table Name, Description -AutoSize

Paso 'Usuarios locales'
$claves = @{}
foreach ($u in @(
    @{Nombre = $Cfg.UsuarioAdmin;    Grupo = $Cfg.GrupoAdmins;     Desc = 'Administrador de MIRS'},
    @{Nombre = $Cfg.UsuarioOperador; Grupo = $Cfg.GrupoOperadores; Desc = 'Operador de MIRS (solo lectura)'})) {
    $clave = Clave-Aleatoria
    $segura = ConvertTo-SecureString $clave -AsPlainText -Force
    if (Get-LocalUser -Name $u.Nombre -ErrorAction SilentlyContinue) {
        Set-LocalUser -Name $u.Nombre -Password $segura
    } else {
        New-LocalUser -Name $u.Nombre -Password $segura -Description $u.Desc -PasswordNeverExpires:$false | Out-Null
    }
    net user $u.Nombre /passwordreq:yes | Out-Null
    $claves[$u.Nombre] = $segura
    if (-not (Get-LocalGroupMember -Group $u.Grupo -Member $u.Nombre -ErrorAction SilentlyContinue)) {
        Add-LocalGroupMember -Group $u.Grupo -Member $u.Nombre
    }
    Add-LocalGroupMember -SID 'S-1-5-32-545' -Member $u.Nombre -ErrorAction SilentlyContinue
}
Write-Host 'Las contraseñas se generaron al azar y no se muestran; cámbielas con Set-LocalUser.'
Get-LocalUser -Name 'mirs_*' | Format-Table Name, Enabled, PasswordRequired, PasswordExpires, Description -AutoSize
foreach ($g in $Cfg.GrupoAdmins, $Cfg.GrupoOperadores) {
    Write-Host "Miembros de ${g}:"
    Get-LocalGroupMember -Group $g | Format-Table Name, PrincipalSource, ObjectClass -AutoSize
}

Paso 'Permisos NTFS sobre C:\MIRS'
# Se corta la herencia para que el grupo Users no tenga acceso por defecto.
$equipo = $env:COMPUTERNAME
icacls $Cfg.Raiz /inheritance:r /grant:r `
    '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' "${equipo}\$($Cfg.GrupoAdmins):(OI)(CI)F" `
    "${equipo}\$($Cfg.GrupoOperadores):(OI)(CI)RX" '*S-1-5-19:(OI)(CI)RX' | Out-Null
# LOCAL SERVICE (S-1-5-19) escribe solo en datos y logs.
icacls $Dir.Datos /grant '*S-1-5-19:(OI)(CI)M' | Out-Null
icacls $Dir.Logs  /grant '*S-1-5-19:(OI)(CI)M' | Out-Null
foreach ($d in $Cfg.Raiz, $Dir.Datos) { icacls $d }

if ($ProbarAcceso) {
    Paso 'Prueba de acceso con la cuenta mirs_operador'
    $cred = New-Object System.Management.Automation.PSCredential($Cfg.UsuarioOperador, $claves[$Cfg.UsuarioOperador])
    $prueba = Join-Path $Dir.Datos 'prueba_operador.txt'
    $salida = Join-Path $env:TEMP 'mirs_prueba_operador.txt'
    $orden = "try { Get-ChildItem '$($Dir.Datos)' | Out-Null; 'LECTURA: permitida' } catch { 'LECTURA: denegada' };" +
             "try { Set-Content -Path '$prueba' -Value x -ErrorAction Stop; 'ESCRITURA: permitida' } catch { 'ESCRITURA: denegada' }"
    $p = Start-Process powershell.exe -Credential $cred -WorkingDirectory $env:SystemRoot -WindowStyle Hidden `
        -ArgumentList '-NoProfile', '-Command', $orden -RedirectStandardOutput $salida -Wait -PassThru
    Write-Host "Proceso ejecutado como $($Cfg.UsuarioOperador) (código de salida $($p.ExitCode)):"
    Get-Content $salida
    Remove-Item $salida -ErrorAction SilentlyContinue
}
