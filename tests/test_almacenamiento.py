import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from mirs.almacenamiento import Almacenamiento  # noqa: E402

EQUIPO = {"nombre_host": "equipo-prueba", "sistema_operativo": "Windows", "version_so": "10.0",
          "nucleos_cpu": 8, "ram_total_mb": 16000, "disco_total_gb": 476.0}
MUESTRA = {"fecha_hora": "2026-11-04T09:00:00", "cpu_pct": 42.0, "ram_pct": 61.5, "disco_pct": 40.2,
           "red_envio_kbps": 12.0, "red_recepcion_kbps": 80.5}
PROCESOS = [
    {"pid": 4120, "nombre": "chrome.exe", "estado": "running", "prioridad": 32,
     "cpu_pct": 20.1, "memoria_mb": 900.0, "hilos": 40},
    {"pid": 2264, "nombre": "python.exe", "estado": "running", "prioridad": 32,
     "cpu_pct": 5.0, "memoria_mb": 60.0, "hilos": 3},
]


class PruebasAlmacenamiento(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.bd = Almacenamiento(Path(self.tmp.name) / "prueba.db")
        self.bd.inicializar()

    def tearDown(self):
        self.tmp.cleanup()

    def test_crea_las_seis_tablas(self):
        with self.bd.sesion() as c:
            tablas = {f[0] for f in c.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
        self.assertEqual(tablas, {"equipo", "umbral", "muestra", "proceso_muestra", "alerta", "accion"})

    def test_registrar_equipo_dos_veces_no_duplica(self):
        a = self.bd.registrar_equipo(EQUIPO)
        b = self.bd.registrar_equipo(EQUIPO)
        self.assertEqual(a, b)

    def test_umbrales_por_defecto_uno_por_recurso(self):
        id_equipo = self.bd.registrar_equipo(EQUIPO)
        self.bd.crear_umbrales_por_defecto(id_equipo)
        self.bd.crear_umbrales_por_defecto(id_equipo)
        with self.bd.sesion() as c:
            total = c.execute("SELECT COUNT(*) FROM umbral WHERE id_equipo = ?", (id_equipo,)).fetchone()[0]
        self.assertEqual(total, 3)

    def test_guardar_muestra_con_sus_procesos(self):
        id_equipo = self.bd.registrar_equipo(EQUIPO)
        id_muestra = self.bd.guardar_muestra(id_equipo, MUESTRA, PROCESOS)
        with self.bd.sesion() as c:
            n = c.execute("SELECT COUNT(*) FROM proceso_muestra WHERE id_muestra = ?", (id_muestra,)).fetchone()[0]
        self.assertEqual(n, 2)
        self.assertEqual(self.bd.ultimas_muestras(id_equipo)[0]["cpu_pct"], 42.0)

    def test_llave_foranea_rechaza_equipo_inexistente(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.bd.guardar_muestra(999, MUESTRA, [])

    def test_check_rechaza_porcentaje_fuera_de_rango(self):
        id_equipo = self.bd.registrar_equipo(EQUIPO)
        with self.assertRaises(sqlite3.IntegrityError):
            self.bd.guardar_muestra(id_equipo, {**MUESTRA, "cpu_pct": 150.0}, [])

    def test_muestra_fallida_no_deja_procesos_huerfanos(self):
        id_equipo = self.bd.registrar_equipo(EQUIPO)
        malo = [{**PROCESOS[0], "memoria_mb": -1.0}]  # viola el CHECK de memoria
        with self.assertRaises(sqlite3.IntegrityError):
            self.bd.guardar_muestra(id_equipo, MUESTRA, malo)
        with self.bd.sesion() as c:
            self.assertEqual(c.execute("SELECT COUNT(*) FROM muestra").fetchone()[0], 0)


if __name__ == "__main__":
    unittest.main()
