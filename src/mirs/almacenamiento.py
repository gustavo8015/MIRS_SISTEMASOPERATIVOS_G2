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

    # ---------- consultas para el análisis, el planificador y la API ----------

    def equipo(self, id_equipo: int) -> dict | None:
        with self.sesion() as c:
            fila = c.execute("SELECT * FROM equipo WHERE id_equipo = ?", (id_equipo,)).fetchone()
            return dict(fila) if fila else None

    def umbrales(self, id_equipo: int) -> list[dict]:
        with self.sesion() as c:
            return [dict(f) for f in c.execute(
                "SELECT * FROM umbral WHERE id_equipo = ? AND activo = 1 ORDER BY recurso", (id_equipo,))]

    def muestras(self, id_equipo: int, cantidad: int = 150) -> list[dict]:
        """Las últimas `cantidad` muestras en orden cronológico (de la más antigua a la más reciente)."""
        filas = self.ultimas_muestras(id_equipo, cantidad)
        return [dict(f) for f in reversed(filas)]

    def procesos_de_muestra(self, id_muestra: int) -> list[dict]:
        with self.sesion() as c:
            return [dict(f) for f in c.execute(
                """SELECT pid, nombre, estado, prioridad, cpu_pct, memoria_mb, hilos
                   FROM proceso_muestra WHERE id_muestra = ?
                   ORDER BY cpu_pct DESC, memoria_mb DESC""", (id_muestra,))]

    def historial_proceso(self, id_equipo: int, pid: int, nombre: str, cantidad: int) -> list[float]:
        """Memoria (MB) del mismo proceso en sus últimas apariciones, en orden cronológico."""
        with self.sesion() as c:
            filas = c.execute(
                """SELECT pm.memoria_mb FROM proceso_muestra pm
                   JOIN muestra m ON m.id_muestra = pm.id_muestra
                   WHERE m.id_equipo = ? AND pm.pid = ? AND pm.nombre = ?
                   ORDER BY m.fecha_hora DESC, m.id_muestra DESC LIMIT ?""",
                (id_equipo, pid, nombre, cantidad)).fetchall()
        return [f[0] for f in reversed(filas)]

    def alertas(self, id_equipo: int, solo_activas: bool = False, cantidad: int = 50) -> list[dict]:
        consulta = """SELECT a.*, m.fecha_hora FROM alerta a
                      JOIN muestra m ON m.id_muestra = a.id_muestra
                      WHERE m.id_equipo = ?"""
        if solo_activas:
            consulta += " AND a.estado = 'ACTIVA'"
        consulta += " ORDER BY a.id_alerta DESC LIMIT ?"
        with self.sesion() as c:
            return [dict(f) for f in c.execute(consulta, (id_equipo, cantidad))]

    def hay_alerta_activa(self, id_equipo: int, tipo: str, severidad: str, pid: int | None = None) -> bool:
        consulta = """SELECT 1 FROM alerta a JOIN muestra m ON m.id_muestra = a.id_muestra
                      WHERE m.id_equipo = ? AND a.tipo = ? AND a.severidad = ? AND a.estado = 'ACTIVA'"""
        args = [id_equipo, tipo, severidad]
        if pid is not None:
            consulta += " AND a.pid = ?"
            args.append(pid)
        with self.sesion() as c:
            return c.execute(consulta + " LIMIT 1", args).fetchone() is not None

    def guardar_alerta(self, alerta: dict) -> int:
        datos = {"id_umbral": None, "pid": None, "nombre_proceso": None, "recomendacion": None, **alerta}
        with self.sesion() as c:
            return c.execute(
                """INSERT INTO alerta (id_muestra, id_umbral, tipo, severidad, pid, nombre_proceso,
                                       mensaje, recomendacion)
                   VALUES (:id_muestra, :id_umbral, :tipo, :severidad, :pid, :nombre_proceso,
                           :mensaje, :recomendacion)""", datos).lastrowid

    def cambiar_estado_alerta(self, id_alerta: int, estado: str) -> bool:
        with self.sesion() as c:
            return c.execute("UPDATE alerta SET estado = ? WHERE id_alerta = ?",
                             (estado, id_alerta)).rowcount == 1

