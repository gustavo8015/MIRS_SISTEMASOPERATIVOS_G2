"""Lectura del estado del equipo a través de psutil.

psutil obtiene los datos del sistema operativo: en Linux lee /proc y /sys,
y en Windows consulta la API de Win32. El módulo solo lee; no modifica procesos.
"""
from __future__ import annotations

import os
import platform
import socket
import time
from datetime import datetime

import psutil


def _raiz_disco() -> str:
    """Unidad del sistema: C:\\ en Windows y / en Linux."""
    if os.name == "nt":
        return os.environ.get("SystemDrive", "C:") + "\\"
    return "/"


def datos_equipo() -> dict:
    """Características fijas del equipo, para la tabla equipo."""
    return {
        "nombre_host": socket.gethostname(),
        "sistema_operativo": platform.system(),
        "version_so": platform.version(),
        "nucleos_cpu": psutil.cpu_count(logical=True) or 1,
        "ram_total_mb": round(psutil.virtual_memory().total / 1024**2),
        "disco_total_gb": round(psutil.disk_usage(_raiz_disco()).total / 1024**3, 1),
    }


def tomar_muestra(intervalo: float = 1.0) -> dict:
    """Uso global de CPU, RAM, disco y red medido durante `intervalo` segundos."""
    red_antes = psutil.net_io_counters()
    inicio = time.monotonic()
    cpu = psutil.cpu_percent(interval=intervalo)  # bloquea durante el intervalo
    transcurrido = max(time.monotonic() - inicio, 1e-6)
    red_despues = psutil.net_io_counters()
    return {
        "fecha_hora": datetime.now().isoformat(timespec="seconds"),
        "cpu_pct": cpu,
        "ram_pct": psutil.virtual_memory().percent,
        "disco_pct": psutil.disk_usage(_raiz_disco()).percent,
        "red_envio_kbps": round((red_despues.bytes_sent - red_antes.bytes_sent) * 8 / 1000 / transcurrido, 1),
        "red_recepcion_kbps": round((red_despues.bytes_recv - red_antes.bytes_recv) * 8 / 1000 / transcurrido, 1),
    }


def listar_procesos(top: int = 10, espera: float = 0.5) -> list[dict]:
    """Los `top` procesos con mayor uso de CPU y memoria.

    psutil calcula el CPU de un proceso entre dos lecturas, así que primero se
    toma una lectura base, se espera `espera` segundos y luego se mide. El valor
    se divide por el número de núcleos para que quede entre 0 y 100.
    """
    nucleos = psutil.cpu_count(logical=True) or 1
    procesos = []
    for p in psutil.process_iter():
        try:
            p.cpu_percent(None)
            procesos.append(p)
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue
    time.sleep(espera)

    resultado = []
    for p in procesos:
        try:
            with p.oneshot():
                resultado.append({
                    "pid": p.pid,
                    "nombre": p.name() or f"pid-{p.pid}",
                    "estado": p.status(),
                    "prioridad": p.nice(),
                    "cpu_pct": round(p.cpu_percent(None) / nucleos, 1),
                    "memoria_mb": round(p.memory_info().rss / 1024**2, 1),
                    "hilos": p.num_threads(),
                })
        except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
            # El proceso terminó entre las dos lecturas o pertenece a otro usuario.
            continue
    resultado.sort(key=lambda d: (d["cpu_pct"], d["memoria_mb"]), reverse=True)
    return resultado[:top]
