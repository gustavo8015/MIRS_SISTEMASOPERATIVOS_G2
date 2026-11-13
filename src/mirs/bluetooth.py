"""Puente Bluetooth: atiende a la app MIRS Móvil por Bluetooth, sin red.

Windows publica el perfil de puerto serie (SPP, RFCOMM) de Bluetooth como un "puerto COM entrante"
(por ejemplo COM5). El celular emparejado se conecta a ese servicio con la UUID estándar de SPP y
este módulo lee y escribe en el puerto con pyserial.

Protocolo de líneas (UTF-8, una petición y una respuesta por línea):
    App  -> PC:  GET /api/estado
                 POST /api/alertas/7 {"estado": "ATENDIDA"}
    PC  -> App:  200 {"equipo": {...}, ...}

Cada petición se reenvía a la API HTTP local (127.0.0.1), así que por Bluetooth y por red se ven
exactamente los mismos datos.

Detección automática: con el puerto "auto" no hay que escribir el número. En Windows, el
identificador de cada puerto COM de Bluetooth empieza por BTHENUM y termina con la dirección del
dispositivo remoto; el puerto entrante no tiene dispositivo remoto, así que su dirección es
000000000000. Por ejemplo:
    entrante: BTHENUM\{00001101-...}_LOCALMFG&0000\7&2E2C4F1&0&000000000000_00000000
    saliente: BTHENUM\{00001101-...}_LOCALMFG&0002\7&2E2C4F1&0&D0C5F3A1B2C3_C00000000
"""
from __future__ import annotations

import logging
import re
import threading
import urllib.error
import urllib.request

log = logging.getLogger("mirs.bluetooth")

AUTO = "auto"
_DIRECCION = re.compile(r"&([0-9A-F]{12})_[0-9A-F]+$", re.I)


def es_entrante(hwid: str) -> bool:
    """True si el identificador corresponde a un puerto COM entrante de Bluetooth."""
    if "BTHENUM" not in hwid.upper():
        return False
    coincidencia = _DIRECCION.search(hwid.strip())
    return bool(coincidencia) and coincidencia.group(1) == "000000000000"


def detectar_puerto(listar=None) -> str | None:
    """Devuelve el puerto COM entrante de Bluetooth (por ejemplo "COM8") o None si no hay.

    `listar` permite probar la función sin Windows; por defecto usa serial.tools.list_ports.
    """
    if listar is None:
        try:
            from serial.tools import list_ports
        except ImportError:
            return None
        listar = list_ports.comports
    try:
        puertos = list(listar())
    except Exception:  # en Linux sin pyserial completo o sin permisos
        return None
    entrantes = sorted((p.device for p in puertos if es_entrante(p.hwid or "")),
                       key=lambda d: int(re.sub(r"\D", "", d) or 0))
    return entrantes[0] if entrantes else None


def atender(linea: str, url_api: str) -> str:
    """Convierte una línea de la app en una petición a la API local y devuelve la respuesta."""
    metodo, _, resto = linea.strip().partition(" ")
    ruta, _, cuerpo = resto.partition(" ")
    metodo = metodo.upper()
    if metodo not in ("GET", "POST") or not ruta.startswith("/api/"):
        return '400 {"error": "Petición inválida"}'
    datos = cuerpo.encode("utf-8") if metodo == "POST" else None
    peticion = urllib.request.Request(url_api + ruta, data=datos, method=metodo,
                                      headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(peticion, timeout=5) as r:
            return f"{r.status} {r.read().decode('utf-8')}"
    except urllib.error.HTTPError as e:
        return f"{e.code} {e.read().decode('utf-8')}"
    except (urllib.error.URLError, OSError):
        return '503 {"error": "La API local no responde"}'


class PuenteBluetooth(threading.Thread):
    """Lee peticiones del puerto COM de Bluetooth y escribe las respuestas."""

    def __init__(self, puerto: str, url_api: str, abrir=None, detectar=detectar_puerto):
        super().__init__(name="mirs-bluetooth", daemon=True)
        self.automatico = puerto.lower() == AUTO
        self.puerto = None if self.automatico else puerto
        self._detectar = detectar
        self.url_api = url_api.rstrip("/")
        self._abrir = abrir or self._abrir_serial
        self._detener = threading.Event()
        self.atendidas = 0

    @staticmethod
    def _abrir_serial(puerto: str):
        import serial  # pyserial; solo se necesita si se usa Bluetooth
        return serial.Serial(puerto, 115200, timeout=0.5, write_timeout=5)

    def run(self) -> None:
        avisado = False
        sin_puerto_avisado = False
        while not self._detener.is_set():
            if self.automatico:
                encontrado = self._detectar()
                if encontrado is None:
                    if not sin_puerto_avisado:
                        log.info("Bluetooth: no hay un puerto COM entrante. Para usar Bluetooth créelo en "
                                 "Más opciones de Bluetooth > Puertos COM > Agregar > Entrante "
                                 "(se detecta solo, sin reiniciar).")
                        sin_puerto_avisado = True
                    self._detener.wait(10)
                    continue
                if encontrado != self.puerto:
                    log.info("Bluetooth: puerto entrante detectado: %s", encontrado)
                self.puerto = encontrado
                sin_puerto_avisado = False
            try:
                conexion = self._abrir(self.puerto)
            except Exception as error:  # el puerto no existe o está ocupado
                if not avisado:
                    log.warning("No se pudo abrir %s (%s). Reintento cada 5 s.", self.puerto, error)
                    avisado = True
                self._detener.wait(5)
                continue
            avisado = False
            log.info("Bluetooth: esperando la app en %s", self.puerto)
            try:
                self._atender_conexion(conexion)
            except Exception as error:  # el celular se desconectó o el puerto se cerró
                log.info("Bluetooth: conexión terminada (%s)", error)
            finally:
                try:
                    conexion.close()
                except Exception:
                    pass
            self._detener.wait(1)

    def _atender_conexion(self, conexion) -> None:
        pendiente = b""
        while not self._detener.is_set():
            trozo = conexion.read(4096)
            if not trozo:
                continue
            pendiente += trozo
            while b"\n" in pendiente:
                linea, pendiente = pendiente.split(b"\n", 1)
                texto = linea.decode("utf-8", errors="replace").strip()
                if not texto:
                    continue
                respuesta = atender(texto, self.url_api)
                conexion.write((respuesta + "\n").encode("utf-8"))
                self.atendidas += 1

    def detener(self) -> None:
        self._detener.set()
