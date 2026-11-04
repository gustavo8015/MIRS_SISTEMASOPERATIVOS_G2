"""Planificador de muestreo: hilo en segundo plano que recolecta, guarda y analiza."""
from __future__ import annotations

import logging
import threading

from . import config
from .almacenamiento import Almacenamiento
from .analizador import analizar
from .recolector import datos_equipo, listar_procesos, tomar_muestra

log = logging.getLogger("mirs.planificador")


class Planificador(threading.Thread):
    def __init__(self, bd: Almacenamiento, intervalo: float = config.INTERVALO_SEG):
        super().__init__(name="mirs-muestreo", daemon=True)
        self.bd = bd
        self.intervalo = intervalo
        self._detener = threading.Event()
        self.id_equipo = bd.registrar_equipo(datos_equipo())
        bd.crear_umbrales_por_defecto(self.id_equipo)
        self.muestras_tomadas = 0

    def run(self) -> None:
        # cpu_percent mide durante una parte del intervalo y la lista de procesos usa el resto
        medicion = max(self.intervalo - 0.5, 0.5)
        while not self._detener.is_set():
            try:
                muestra = tomar_muestra(medicion)
                procesos = listar_procesos(config.TOP_PROCESOS, espera=0.4)
                id_muestra = self.bd.guardar_muestra(self.id_equipo, muestra, procesos)
                nuevas = analizar(self.bd, self.id_equipo, id_muestra)
                self.muestras_tomadas += 1
                if nuevas:
                    log.info("%d alerta(s) nueva(s)", len(nuevas))
            except Exception:  # un error en una muestra no debe detener el monitoreo
                log.exception("Error al tomar la muestra")
                self._detener.wait(self.intervalo)

    def detener(self) -> None:
        self._detener.set()
