#!/usr/bin/env bash
# Instala MIRS como servicio systemd en Linux (Ubuntu/Debian) con una cuenta sin privilegios,
# grupo de operadores, permisos restringidos y firewall (ufw). Ejecutar con sudo desde la raíz del repositorio.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
paso() { printf '\n=== %s %s\n' "$1" "$(printf '=%.0s' $(seq 1 $((70 - ${#1}))))"; }

paso "Sistema operativo"
. /etc/os-release; echo "$PRETTY_NAME | kernel $(uname -r) | $(nproc) núcleos | $(free -h | awk '/Mem:/{print $2}') RAM"

paso "Usuarios y grupos"
id mirs >/dev/null 2>&1 || useradd --system --home-dir /var/lib/mirs --shell /usr/sbin/nologin mirs
getent group mirs-operadores >/dev/null || groupadd mirs-operadores
id mirs_operador >/dev/null 2>&1 || useradd --create-home --shell /bin/bash --groups mirs-operadores mirs_operador
id mirs; id mirs_operador

paso "Instalación en /opt/mirs y datos en /var/lib/mirs"
install -d -m 0755 /opt/mirs /opt/mirs/app
cp -r "$REPO/src" "$REPO/database" "$REPO/mirs_servidor.py" /opt/mirs/app/
python3 -m venv /opt/mirs/venv
/opt/mirs/venv/bin/pip install --quiet psutil pyserial
install -d -o mirs -g mirs-operadores -m 2750 /var/lib/mirs
install -d -o root -g mirs-operadores -m 2750 /var/backups/mirs
ls -ld /opt/mirs /var/lib/mirs /var/backups/mirs

paso "Servicio systemd"
install -m 0644 "$REPO/config/mirs.service" /etc/systemd/system/mirs.service
systemctl daemon-reload
systemctl enable --now mirs.service
for _ in $(seq 1 30); do curl -sf http://127.0.0.1:8765/api/estado >/dev/null && break; sleep 1; done
systemctl status mirs.service --no-pager | head -12

paso "Firewall (ufw)"
SUBRED="$(ip -o -f inet addr show scope global | awk '{print $4}' | head -1)"
ufw --force reset >/dev/null
ufw default deny incoming >/dev/null; ufw default allow outgoing >/dev/null
ufw allow 22/tcp comment 'SSH administración' >/dev/null
ufw allow from "$SUBRED" to any port 8765 proto tcp comment 'MIRS API' >/dev/null
ufw allow from "$SUBRED" to any port 8766 proto udp comment 'MIRS búsqueda' >/dev/null
ufw --force enable >/dev/null
ufw status verbose
