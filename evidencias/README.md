# Evidencias

Salidas reales de los scripts de `scripts/`, ejecutados en máquinas limpias de GitHub Actions
(flujo [Evidencias de administración](../.github/workflows/evidencias.yml),
ejecución [43519950204](https://github.com/gustavo8015/MIRS_SISTEMASOPERATIVOS_G2/actions/runs/43519950204),
13 de noviembre de 2026). Las horas de los registros están en UTC.

| Carpeta | Equipo | Contenido |
|---|---|---|
| `windows/` | Windows Server 2025 Datacenter, 4 vCPU, 16 GB | Un archivo por script (01 a 11): entorno, instalación, seguridad, usuarios y permisos, tarea programada, firewall, respaldo, vigilante, monitoreo, pruebas (13/13) y auditoría |
| `linux/` | Ubuntu 26.04 LTS, 4 vCPU, 16 GB | Instalación como servicio systemd y pruebas (9/9) |
| `pruebas_unitarias.txt` | Entorno de desarrollo | 38 pruebas automáticas del servidor (`python -m unittest`) |
