# Funciones y configuración compartidas por los scripts de MIRS en Windows.
# Se carga con:  . "$PSScriptRoot\00_comun.ps1"
$ErrorActionPreference = 'Stop'
$RaizRepo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$Cfg = Import-PowerShellDataFile (Join-Path $RaizRepo 'config\mirs-windows.psd1')
$Dir = @{
    Bin       = Join-Path $Cfg.Raiz 'bin'
    Datos     = Join-Path $Cfg.Raiz 'datos'
    Respaldos = Join-Path $Cfg.Raiz 'respaldos'
    Logs      = Join-Path $Cfg.Raiz 'logs'
}
$RutaExe = Join-Path $Dir.Bin $Cfg.Ejecutable
$RutaBD  = Join-Path $Dir.Datos 'mirs.db'
$UrlApi  = "http://127.0.0.1:$($Cfg.PuertoApi)/api/estado"

function Paso([string]$texto) {
    Write-Host ''
    Write-Host ('=== ' + $texto + ' ' + ('=' * [Math]::Max(0, 70 - $texto.Length)))
}

function Requiere-Admin {
    $id = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not $id.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Ejecute este script en una consola de PowerShell abierta como administrador.'
    }
}

function Esperar-Api([int]$segundos = 40) {
    $limite = (Get-Date).AddSeconds($segundos)
    while ((Get-Date) -lt $limite) {
        try { return Invoke-RestMethod -Uri $UrlApi -TimeoutSec 3 } catch { Start-Sleep -Seconds 2 }
    }
    return $null
}

function Escribir-Log([string]$archivo, [string]$texto) {
    New-Item -ItemType Directory -Force -Path $Dir.Logs | Out-Null
    $linea = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ' + $texto
    Add-Content -Path (Join-Path $Dir.Logs $archivo) -Value $linea -Encoding UTF8
    Write-Host $linea
}
