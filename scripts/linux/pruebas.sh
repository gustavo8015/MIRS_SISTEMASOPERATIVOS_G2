#!/usr/bin/env bash
# Pruebas del servicio MIRS en Linux: proceso, permisos, red, respaldo y recuperación ante fallos.
set -uo pipefail
API=http://127.0.0.1:8765
ok=0; fallas=0
probar() { local id="$1" nombre="$2"; shift 2
  if detalle="$("$@" 2>&1)"; then ok=$((ok+1)); printf '%-4s OK     %-55s %s\n' "$id" "$nombre" "$detalle"
  else fallas=$((fallas+1)); printf '%-4s FALLA  %-55s %s\n' "$id" "$nombre" "$detalle"; fi; }

p1() { systemctl is-active mirs.service; }
p2() { u=$(ps -o user= -p "$(systemctl show -p MainPID --value mirs.service)"); [ "$u" = mirs ] && echo "proceso como $u"; }
p3() { curl -sf "$API/api/estado" | python3 -c 'import json,sys; m=json.load(sys.stdin)["muestra"]; print(f"CPU {m[\"cpu_pct\"]} %, RAM {m[\"ram_pct\"]} %")'; }
p4() { if sudo -u mirs_operador touch /var/lib/mirs/x 2>/dev/null; then echo "pudo escribir"; return 1; fi
       sudo -u mirs_operador ls /var/lib/mirs >/dev/null && echo "lee, no escribe"; }
p5() { if sudo -u nobody ls /var/lib/mirs >/dev/null 2>&1; then echo "nobody pudo leer"; return 1; fi; echo "otros sin acceso"; }
p6() { ss -ltnup | grep -E ':8765|:8766' | awk '{print $1, $5}' | tr '\n' ' '; }
p7() { d=/var/backups/mirs/mirs_$(date +%Y%m%d_%H%M%S).db
       /opt/mirs/venv/bin/python /opt/mirs/app/mirs_servidor.py --bd /var/lib/mirs/mirs.db --respaldo "$d" --sin-bluetooth | tail -1; }
p8() { a=$(systemctl show -p MainPID --value mirs.service); kill -9 "$a"; sleep 8
       b=$(systemctl show -p MainPID --value mirs.service); [ "$b" != 0 ] && [ "$b" != "$a" ] && curl -sf "$API/api/estado" >/dev/null && echo "PID $a terminado, systemd lo reinició como $b"; }
p9() { systemd-analyze security mirs.service 2>/dev/null | tail -1; }

probar L01 "Servicio mirs activo" p1
probar L02 "El proceso corre con la cuenta sin privilegios mirs" p2
probar L03 "API responde con una muestra válida" p3
probar L04 "Operador lee los datos pero no puede escribir" p4
probar L05 "Otros usuarios sin acceso a /var/lib/mirs" p5
probar L06 "Puertos TCP 8765 y UDP 8766 escuchando" p6
probar L07 "Respaldo en caliente con verificación de integridad" p7
probar L08 "Recuperación ante fallo (Restart=on-failure)" p8
probar L09 "Exposición del servicio según systemd-analyze" p9
echo "Pruebas: $((ok+fallas))  OK: $ok  FALLA: $fallas"
[ "$fallas" -eq 0 ]
