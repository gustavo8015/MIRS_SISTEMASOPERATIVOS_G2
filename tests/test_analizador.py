import sys
import unittest
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from mirs.analizador import evaluar_desviacion, evaluar_fuga, evaluar_umbrales  # noqa: E402

INICIO = datetime(2026, 11, 4, 9, 0, 0)
UMBRAL_CPU = {"id_umbral": 1, "recurso": "CPU", "valor_limite": 85.0, "duracion_seg": 60}
UMBRAL_DISCO = {"id_umbral": 3, "recurso": "DISCO", "valor_limite": 90.0, "duracion_seg": 0}


def serie(cpus, ram=50.0, disco=40.0, paso=2):
    return [{"fecha_hora": (INICIO + timedelta(seconds=i * paso)).isoformat(),
             "cpu_pct": c, "ram_pct": ram, "disco_pct": disco} for i, c in enumerate(cpus)]


class PruebasUmbral(unittest.TestCase):
    def test_cpu_sostenida_genera_alerta_alta(self):
        alertas = evaluar_umbrales(serie([90.0] * 31), [UMBRAL_CPU])  # 60 s sobre 85 %
        self.assertEqual(len(alertas), 1)
        self.assertEqual((alertas[0]["tipo"], alertas[0]["severidad"]), ("PICO_CPU", "ALTA"))

    def test_pico_corto_no_genera_alerta(self):
        alertas = evaluar_umbrales(serie([30.0] * 25 + [95.0] * 6), [UMBRAL_CPU])  # solo 10 s arriba
        self.assertEqual(alertas, [])

    def test_sin_historial_suficiente_no_alerta(self):
        self.assertEqual(evaluar_umbrales(serie([95.0] * 5), [UMBRAL_CPU]), [])

    def test_umbral_sin_duracion_alerta_de_inmediato(self):
        alertas = evaluar_umbrales(serie([10.0], disco=93.0), [UMBRAL_DISCO])
        self.assertEqual(alertas[0]["tipo"], "DISCO_LLENO")


class PruebasDesviacion(unittest.TestCase):
    def test_valor_muy_por_encima_del_promedio(self):
        cpus = [20.0 + (i % 3) for i in range(30)] + [70.0]
        tipos = [a["tipo"] for a in evaluar_desviacion(serie(cpus))]
        self.assertIn("PICO_CPU", tipos)

    def test_variacion_normal_no_alerta(self):
        cpus = [20.0 + (i % 5) * 3 for i in range(30)] + [30.0]
        self.assertEqual(evaluar_desviacion(serie(cpus)), [])


class PruebasFuga(unittest.TestCase):
    PROCESO = {"pid": 2264, "nombre": "python.exe"}

    def test_crecimiento_continuo_es_posible_fuga(self):
        alerta = evaluar_fuga(self.PROCESO, [200.0 + 40 * i for i in range(10)])  # 200 -> 560 MB
        self.assertEqual(alerta["tipo"], "POSIBLE_FUGA_MEMORIA")
        self.assertEqual(alerta["pid"], 2264)

    def test_memoria_que_baja_no_es_fuga(self):
        memorias = [200.0 + 40 * i for i in range(10)]
        memorias[5] = 150.0
        self.assertIsNone(evaluar_fuga(self.PROCESO, memorias))

    def test_crecimiento_pequeno_no_es_fuga(self):
        self.assertIsNone(evaluar_fuga(self.PROCESO, [500.0 + i for i in range(10)]))


if __name__ == "__main__":
    unittest.main()
