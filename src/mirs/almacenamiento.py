"""Persistencia del historial en SQLite (módulo sqlite3 de la biblioteca estándar)."""
from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from pathlib import Path

from . import config


class Almacenamiento:
    def __init__(self, ruta: str | Path = config.RUTA_BD):
        self.ruta = Path(ruta)
        self.ruta.parent.mkdir(parents=True, exist_ok=True)

    def conectar(self) -> sqlite3.Connection:
        conexion = sqlite3.connect(self.ruta)
        conexion.row_factory = sqlite3.Row
        conexion.execute("PRAGMA foreign_keys = ON")
        conexion.execute("PRAGMA journal_mode = WAL")  # lectura de la interfaz mientras se escribe
        return conexion

    @contextmanager
    def sesion(self):
        """Abre una conexión, confirma al salir (o revierte si hubo error) y la cierra.

        Cerrar la conexión importa en Windows, donde un archivo abierto queda bloqueado.
        """
        conexion = self.conectar()
        try:
            with conexion:  # commit o rollback
                yield conexion
        finally:
            conexion.close()

    def inicializar(self) -> None:
        """Crea las tablas si no existen."""
        esquema = config.RUTA_ESQUEMA.read_text(encoding="utf-8")
        with self.sesion() as c:
            c.executescript(esquema)

    def registrar_equipo(self, datos: dict) -> int:
        """Inserta el equipo o actualiza sus datos si el host ya existe."""
        with self.sesion() as c:
            c.execute(
                """INSERT INTO equipo (nombre_host, sistema_operativo, version_so,
                                       nucleos_cpu, ram_total_mb, disco_total_gb)
                   VALUES (:nombre_host, :sistema_operativo, :version_so,
                           :nucleos_cpu, :ram_total_mb, :disco_total_gb)
                   ON CONFLICT (nombre_host) DO UPDATE SET
                       sistema_operativo = excluded.sistema_operativo,
                       version_so = excluded.version_so,
                       nucleos_cpu = excluded.nucleos_cpu,
                       ram_total_mb = excluded.ram_total_mb,
                       disco_total_gb = excluded.disco_total_gb""",
                datos,
            )
            fila = c.execute("SELECT id_equipo FROM equipo WHERE nombre_host = ?",
                             (datos["nombre_host"],)).fetchone()
            return fila["id_equipo"]

    def crear_umbrales_por_defecto(self, id_equipo: int) -> None:
        with self.sesion() as c:
            c.executemany(
                """INSERT OR IGNORE INTO umbral (id_equipo, recurso, valor_limite, duracion_seg)
                   VALUES (?, ?, ?, ?)""",
                [(id_equipo, r, v, d) for r, (v, d) in config.UMBRALES_POR_DEFECTO.items()],
            )

    def guardar_muestra(self, id_equipo: int, muestra: dict, procesos: list[dict]) -> int:
        """Guarda la muestra y sus procesos en una sola transacción."""
        with self.sesion() as c:
            cursor = c.execute(
                """INSERT INTO muestra (id_equipo, fecha_hora, cpu_pct, ram_pct, disco_pct,
                                        red_envio_kbps, red_recepcion_kbps)
                   VALUES (:id_equipo, :fecha_hora, :cpu_pct, :ram_pct, :disco_pct,
                           :red_envio_kbps, :red_recepcion_kbps)""",
                {"id_equipo": id_equipo, **muestra},
            )
            id_muestra = cursor.lastrowid
            c.executemany(
                """INSERT INTO proceso_muestra (id_muestra, pid, nombre, estado, prioridad,
                                                cpu_pct, memoria_mb, hilos)
                   VALUES (:id_muestra, :pid, :nombre, :estado, :prioridad,
                           :cpu_pct, :memoria_mb, :hilos)""",
                [{"id_muestra": id_muestra, **p} for p in procesos],
            )
            return id_muestra

    def ultimas_muestras(self, id_equipo: int, cantidad: int = 30) -> list[sqlite3.Row]:
        with self.sesion() as c:
            return c.execute(
                """SELECT * FROM muestra WHERE id_equipo = ?
                   ORDER BY fecha_hora DESC, id_muestra DESC LIMIT ?""",
                (id_equipo, cantidad),
            ).fetchall()
