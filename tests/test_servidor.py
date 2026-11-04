import json
import sys
import tempfile
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from mirs.almacenamiento import Almacenamiento  # noqa: E402
from mirs.servidor import crear_manejador  # noqa: E402

EQUIPO = {"nombre_host": "equipo-prueba", "sistema_operativo": "Windows", "version_so": "10.0",
          "nucleos_cpu": 8, "ram_total_mb": 16000, "disco_total_gb": 476.0}


class PruebasApi(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.bd = Almacenamiento(Path(self.tmp.name) / "api.db")
        self.bd.inicializar()
        self.id_equipo = self.bd.registrar_equipo(EQUIPO)
        self.bd.crear_umbrales_por_defecto(self.id_equipo)
        id_muestra = self.bd.guardar_muestra(
            self.id_equipo,
            {"fecha_hora": "2026-11-04T09:00:00", "cpu_pct": 87.0, "ram_pct": 71.0, "disco_pct": 41.0,
             "red_envio_kbps": 120.0, "red_recepcion_kbps": 2340.0},
            [{"pid": 4120, "nombre": "chrome.exe", "estado": "running", "prioridad": 32,
              "cpu_pct": 52.4, "memoria_mb": 1812.3, "hilos": 48}])
        self.id_alerta = self.bd.guardar_alerta({"id_muestra": id_muestra, "tipo": "PICO_CPU",
                                                 "severidad": "ALTA", "mensaje": "CPU alta"})
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), crear_manejador(self.bd, self.id_equipo))
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}"

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        self.tmp.cleanup()

    def get(self, ruta):
        with urllib.request.urlopen(self.base + ruta) as r:
            return json.loads(r.read().decode("utf-8"))

    def post(self, ruta, cuerpo):
        req = urllib.request.Request(self.base + ruta, data=json.dumps(cuerpo).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_estado_trae_equipo_muestra_y_procesos(self):
        e = self.get("/api/estado")
        self.assertEqual(e["equipo"]["nombre_host"], "equipo-prueba")
        self.assertEqual(e["muestra"]["cpu_pct"], 87.0)
        self.assertEqual(e["procesos"][0]["nombre"], "chrome.exe")
        self.assertEqual(e["alertas_activas"], 1)

    def test_muestras_y_umbrales(self):
        self.assertEqual(len(self.get("/api/muestras?cantidad=10")), 1)
        self.assertEqual({u["recurso"] for u in self.get("/api/umbrales")}, {"CPU", "RAM", "DISCO"})

    def test_marcar_alerta_atendida(self):
        codigo, r = self.post(f"/api/alertas/{self.id_alerta}", {"estado": "ATENDIDA"})
        self.assertEqual((codigo, r["estado"]), (200, "ATENDIDA"))
        self.assertEqual(self.get("/api/alertas?estado=ACTIVA"), [])
        self.assertEqual(len(self.get("/api/alertas?estado=TODAS")), 1)

    def test_estado_invalido_y_alerta_inexistente(self):
        self.assertEqual(self.post(f"/api/alertas/{self.id_alerta}", {"estado": "BORRAR"})[0], 400)
        self.assertEqual(self.post("/api/alertas/999", {"estado": "ATENDIDA"})[0], 404)


if __name__ == "__main__":
    unittest.main()
