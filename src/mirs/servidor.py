"""Servidor de MIRS: monitorea el PC y publica los datos para la app móvil.

Uso (desde la carpeta del repositorio, con PYTHONPATH=src):
    python -m mirs.servidor                 # solo este PC y el emulador de Android (127.0.0.1:8765)
    python -m mirs.servidor --lan           # también un celular en la misma red WiFi (0.0.0.0:8765)

El emulador de Android Studio llega al PC con la dirección http://10.0.2.2:8765.
La API es de solo lectura, salvo marcar una alerta como atendida o descartada.
No tiene autenticación: úsela solo en una red de confianza.

Rutas (todas responden JSON):
    GET  /api/estado                 equipo, última muestra, procesos de mayor consumo y alertas activas
    GET  /api/muestras?cantidad=150  historial reciente para la gráfica (orden cronológico)
    GET  /api/procesos               procesos de la última muestra
    GET  /api/alertas?estado=ACTIVA  alertas (ACTIVA o TODAS)
    GET  /api/umbrales               umbrales configurados
    POST /api/alertas/<id>           cuerpo {"estado": "ATENDIDA" | "DESCARTADA"}
"""
from __future__ import annotations

import argparse
import json
import logging
import re
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from . import __version__, config
from .almacenamiento import Almacenamiento

log = logging.getLogger("mirs.servidor")


def crear_manejador(bd: Almacenamiento, id_equipo: int, intervalo: float = config.INTERVALO_SEG):
    class Manejador(BaseHTTPRequestHandler):
        server_version = f"MIRS/{__version__}"

        def log_message(self, formato, *args):  # registro breve en consola
            log.debug("%s %s", self.address_string(), formato % args)

        def _json(self, cuerpo, codigo: int = 200) -> None:
            datos = json.dumps(cuerpo, ensure_ascii=False).encode("utf-8")
            self.send_response(codigo)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(datos)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(datos)

        def do_GET(self):  # noqa: N802 (nombre definido por http.server)
            url = urlparse(self.path)
            q = parse_qs(url.query)
            try:
                if url.path == "/api/estado":
                    muestras = bd.muestras(id_equipo, 1)
                    actual = muestras[-1] if muestras else None
                    self._json({
                        "servidor_hora": datetime.now().isoformat(timespec="seconds"),
                        "intervalo_seg": intervalo,
                        "equipo": bd.equipo(id_equipo),
                        "muestra": actual,
                        "procesos": bd.procesos_de_muestra(actual["id_muestra"])[:5] if actual else [],
                        "alertas_activas": len(bd.alertas(id_equipo, solo_activas=True, cantidad=500)),
                    })
                elif url.path == "/api/muestras":
                    cantidad = min(max(int(q.get("cantidad", ["150"])[0]), 1), 1000)
                    self._json([{k: m[k] for k in ("fecha_hora", "cpu_pct", "ram_pct", "disco_pct")}
                                for m in bd.muestras(id_equipo, cantidad)])
                elif url.path == "/api/procesos":
                    muestras = bd.muestras(id_equipo, 1)
                    self._json(bd.procesos_de_muestra(muestras[-1]["id_muestra"]) if muestras else [])
                elif url.path == "/api/alertas":
                    solo_activas = q.get("estado", ["ACTIVA"])[0].upper() == "ACTIVA"
                    self._json(bd.alertas(id_equipo, solo_activas=solo_activas))
                elif url.path == "/api/umbrales":
                    self._json(bd.umbrales(id_equipo))
                else:
                    self._json({"error": "Ruta no encontrada"}, 404)
            except ValueError:
                self._json({"error": "Parámetro inválido"}, 400)
            except Exception:
                log.exception("Error en %s", self.path)
                self._json({"error": "Error interno del servidor"}, 500)

        def do_POST(self):  # noqa: N802
            coincide = re.fullmatch(r"/api/alertas/(\d+)", urlparse(self.path).path)
            if not coincide:
                return self._json({"error": "Ruta no encontrada"}, 404)
            try:
                largo = int(self.headers.get("Content-Length", "0"))
                cuerpo = json.loads(self.rfile.read(largo) or b"{}")
                estado = str(cuerpo.get("estado", "")).upper()
            except (ValueError, json.JSONDecodeError):
                return self._json({"error": "JSON inválido"}, 400)
            if estado not in ("ATENDIDA", "DESCARTADA"):
                return self._json({"error": "estado debe ser ATENDIDA o DESCARTADA"}, 400)
            if not bd.cambiar_estado_alerta(int(coincide.group(1)), estado):
                return self._json({"error": "La alerta no existe"}, 404)
            self._json({"ok": True, "id_alerta": int(coincide.group(1)), "estado": estado})

    return Manejador


def main() -> None:
    from .planificador import Planificador

    parser = argparse.ArgumentParser(description="MIRS: monitoreo del PC y API para la app móvil.")
    parser.add_argument("--puerto", type=int, default=8765)
    parser.add_argument("--lan", action="store_true", help="aceptar conexiones de otros equipos de la red")
    parser.add_argument("--intervalo", type=float, default=config.INTERVALO_SEG)
    parser.add_argument("--bd", default=str(config.RUTA_BD))
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", datefmt="%H:%M:%S")

    bd = Almacenamiento(args.bd)
    bd.inicializar()
    planificador = Planificador(bd, args.intervalo)
    planificador.start()

    host = "0.0.0.0" if args.lan else "127.0.0.1"
    servidor = ThreadingHTTPServer((host, args.puerto), crear_manejador(bd, planificador.id_equipo, args.intervalo))
    log.info("MIRS %s monitoreando %s cada %.0f s", __version__,
             bd.equipo(planificador.id_equipo)["nombre_host"], args.intervalo)
    log.info("API en http://%s:%d/api/estado  (emulador Android: http://10.0.2.2:%d)", host, args.puerto, args.puerto)
    log.info("Ctrl+C para detener")
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        planificador.detener()
        servidor.server_close()
        log.info("Servidor detenido")


if __name__ == "__main__":
    main()
