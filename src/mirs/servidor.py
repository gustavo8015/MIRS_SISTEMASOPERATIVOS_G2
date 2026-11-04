"""Servidor de MIRS: monitorea el PC y publica los datos para la app móvil.

Uso (desde la carpeta del repositorio, con PYTHONPATH=src):
    python -m mirs.servidor                 # solo este PC y el emulador de Android (127.0.0.1:8765)
    python -m mirs.servidor --lan           # también otros equipos de la red (0.0.0.0:8765)

Con --lan el servidor además responde a la búsqueda de equipos de la app: escucha en UDP 8766
el mensaje MIRS_BUSCAR y contesta con su nombre y su puerto, así la app lo encuentra sin escribir la IP.

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
import socket
import threading
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


def respuesta_descubrimiento(equipo: dict, puerto: int) -> bytes:
    return json.dumps({"servicio": "MIRS", "version": __version__, "nombre_host": equipo["nombre_host"],
                       "sistema_operativo": equipo["sistema_operativo"], "puerto": puerto},
                      ensure_ascii=False).encode("utf-8")


class RespuestaBusqueda(threading.Thread):
    """Responde por UDP a la app cuando pregunta qué servidores de MIRS hay en la red."""

    def __init__(self, equipo: dict, puerto_api: int, puerto_udp: int = config.PUERTO_DESCUBRIMIENTO,
                 host: str = "0.0.0.0"):
        super().__init__(name="mirs-descubrimiento", daemon=True)
        self.respuesta = respuesta_descubrimiento(equipo, puerto_api)
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.bind((host, puerto_udp))
        self.socket.settimeout(1.0)
        self._detener = threading.Event()

    @property
    def puerto(self) -> int:
        return self.socket.getsockname()[1]

    def run(self) -> None:
        while not self._detener.is_set():
            try:
                datos, origen = self.socket.recvfrom(1024)
            except socket.timeout:
                continue
            except ConnectionResetError:
                # Windows avisa así cuando la app ya cerró su socket; no es un error del servidor
                continue
            except OSError:
                break
            if datos.strip() == config.MENSAJE_BUSQUEDA:
                self.socket.sendto(self.respuesta, origen)

    def detener(self) -> None:
        self._detener.set()
        self.socket.close()


def ips_locales() -> list[str]:
    """Direcciones IPv4 del equipo en la red local (para mostrarlas al usuario)."""
    ips = set()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 80))  # no envía datos; solo elige la interfaz de salida
            ips.add(s.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def main(argv: list[str] | None = None, lan_por_defecto: bool = False) -> None:
    from .planificador import Planificador

    parser = argparse.ArgumentParser(description="MIRS: monitoreo del PC y API para la app móvil.")
    parser.add_argument("--puerto", type=int, default=config.PUERTO_API)
    parser.add_argument("--lan", action="store_true", default=lan_por_defecto,
                        help="aceptar conexiones de otros equipos de la red y responder a la búsqueda")
    parser.add_argument("--solo-local", dest="lan", action="store_false",
                        help="atender solo a este PC y al emulador de Android")
    parser.add_argument("--intervalo", type=float, default=config.INTERVALO_SEG)
    parser.add_argument("--bd", default=str(config.RUTA_BD))
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", datefmt="%H:%M:%S")

    bd = Almacenamiento(args.bd)
    bd.inicializar()
    planificador = Planificador(bd, args.intervalo)
    planificador.start()

    host = "0.0.0.0" if args.lan else "127.0.0.1"
    try:
        servidor = ThreadingHTTPServer((host, args.puerto),
                                       crear_manejador(bd, planificador.id_equipo, args.intervalo))
    except OSError:
        log.error("El puerto %d ya está en uso. ¿Hay otro servidor de MIRS abierto?", args.puerto)
        planificador.detener()
        raise SystemExit(1)
    equipo = bd.equipo(planificador.id_equipo)
    busqueda = None
    if args.lan:
        try:
            busqueda = RespuestaBusqueda(equipo, args.puerto)
            busqueda.start()
        except OSError:
            log.warning("No se pudo abrir el puerto UDP %d: la app no encontrará este equipo con "
                        "Buscar equipos, pero puede escribir la dirección a mano.", config.PUERTO_DESCUBRIMIENTO)
    log.info("MIRS %s monitoreando %s cada %.0f s", __version__, equipo["nombre_host"], args.intervalo)
    log.info("API en http://127.0.0.1:%d/api/estado  (emulador Android: http://10.0.2.2:%d)", args.puerto, args.puerto)
    if args.lan:
        for ip in ips_locales():
            log.info("Desde otros equipos o celulares de la red: http://%s:%d", ip, args.puerto)
        log.info("En la app también puede usar Buscar equipos")
    log.info("Ctrl+C para detener")
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        planificador.detener()
        if busqueda:
            busqueda.detener()
        servidor.server_close()
        log.info("Servidor detenido")


if __name__ == "__main__":
    main()
