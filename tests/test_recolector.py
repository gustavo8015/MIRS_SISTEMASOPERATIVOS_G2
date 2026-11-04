import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from mirs.recolector import datos_equipo, listar_procesos, tomar_muestra  # noqa: E402


class PruebasRecolector(unittest.TestCase):
    def test_datos_equipo_completos(self):
        d = datos_equipo()
        for campo in ("nombre_host", "sistema_operativo", "nucleos_cpu", "ram_total_mb", "disco_total_gb"):
            self.assertIn(campo, d)
        self.assertGreater(d["nucleos_cpu"], 0)
        self.assertGreater(d["ram_total_mb"], 0)

    def test_muestra_en_rangos_validos(self):
        m = tomar_muestra(intervalo=0.2)
        for campo in ("cpu_pct", "ram_pct", "disco_pct"):
            self.assertGreaterEqual(m[campo], 0)
            self.assertLessEqual(m[campo], 100)
        self.assertGreaterEqual(m["red_envio_kbps"], 0)
        self.assertGreaterEqual(m["red_recepcion_kbps"], 0)

    def test_listar_procesos_respeta_el_tope_y_el_orden(self):
        procesos = listar_procesos(top=5, espera=0.2)
        self.assertLessEqual(len(procesos), 5)
        self.assertGreater(len(procesos), 0)
        cpu = [p["cpu_pct"] for p in procesos]
        self.assertEqual(cpu, sorted(cpu, reverse=True))
        for p in procesos:
            self.assertGreaterEqual(p["pid"], 0)
            self.assertTrue(p["nombre"])


if __name__ == "__main__":
    unittest.main()
