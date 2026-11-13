# Paso 7 de la guía: contraseñas, bloqueo de cuentas, auditoría, actualizaciones y antimalware.
. "$PSScriptRoot\00_comun.ps1"
Requiere-Admin

Paso 'Política de contraseñas y bloqueo de cuentas locales'
net accounts /minpwlen:$($Cfg.LongitudMinima) /maxpwage:$($Cfg.VigenciaDias) /uniquepw:5 | Out-Null
net accounts /lockoutthreshold:$($Cfg.IntentosBloqueo) /lockoutduration:$($Cfg.MinutosBloqueo) /lockoutwindow:$($Cfg.MinutosBloqueo) | Out-Null
net accounts

Paso 'Auditoría de eventos'
# Se usan los GUID de las subcategorías para que funcione en Windows en cualquier idioma:
# inicio de sesión, bloqueo de cuenta, administración de cuentas, administración de grupos,
# creación de procesos y sistema de archivos (necesaria para la SACL de la carpeta de datos).
$categorias = '{0CCE9215-69AE-11D9-BED3-505054503030}', '{0CCE9217-69AE-11D9-BED3-505054503030}',
              '{0CCE9235-69AE-11D9-BED3-505054503030}', '{0CCE9237-69AE-11D9-BED3-505054503030}',
              '{0CCE922B-69AE-11D9-BED3-505054503030}', '{0CCE921D-69AE-11D9-BED3-505054503030}'
foreach ($c in $categorias) { auditpol /set /subcategory:"$c" /success:enable /failure:enable | Out-Null }
auditpol /get /subcategory:"$($categorias -join ',')"

Paso 'Auditoría de acceso a la base de datos (SACL en C:\MIRS\datos)'
$acl = Get-Acl -Path $Dir.Datos -Audit
$regla = New-Object System.Security.AccessControl.FileSystemAuditRule(
    (New-Object Security.Principal.SecurityIdentifier 'S-1-1-0'), 'Delete,Write,ChangePermissions',
    'ContainerInherit,ObjectInherit', 'None', 'Success,Failure')
$acl.SetAuditRule($regla)
Set-Acl -Path $Dir.Datos -AclObject $acl
(Get-Acl -Path $Dir.Datos -Audit).Audit | Format-Table IdentityReference, FileSystemRights, AuditFlags -AutoSize

Paso 'Actualizaciones instaladas (las más recientes)'
Get-HotFix | Sort-Object InstalledOn -Descending | Select-Object -First 5 HotFixID, Description, InstalledOn | Format-Table -AutoSize

Paso 'Antimalware (Microsoft Defender)'
try {
    Get-MpComputerStatus | Select-Object AMServiceEnabled, AntivirusEnabled, RealTimeProtectionEnabled,
        AntivirusSignatureLastUpdated | Format-List
} catch {
    Write-Host 'Microsoft Defender no está disponible en este equipo.'
}
