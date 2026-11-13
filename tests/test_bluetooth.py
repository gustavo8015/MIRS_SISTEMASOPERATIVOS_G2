import os
import sys
import tempfile
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from mirs.almacenamiento import Almacenamiento  # noqa: E402
from mirs.bluetooth import PuenteBluetooth, atender  # noqa: E402
from mirs.servidor import crear_manejador  # noqa: E402

EQUIPO = {"nombre_host": "portatil-bt", "sistema_operativo": "Windows", "version_so": "10.0",
          "nucleos_cpu": 4, "ram_total_mb": 8000, "disco_total_gb": 256.0}


class ConexionFalsa:
    """Imita un puerto serie: entrega las líneas de la app y guarda lo que el puente escribe."""

    def __init__(self, entrada: bytes):
        self.entrada = [entrada[i:i + 7] for i in range(0, len(entrada), 7)]  # llega por trozos
        self.salida = b""

    def read(self, _n):
        if self.entrada:
            return self.entrada.pop(0)
        time.sleep(0.05)
        return b""

    def write(self, datos):
        self.salida += datos

    def close(self):
        pass


class PruebasPuente(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.bd = Almacenamiento(Path(self.tmp.name) / "bt.db")
        self.bd.inicializar()
        self.id_equipo = self.bd.registrar_equipo(EQUIPO)
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), crear_manejador(self.bd, self.id_equipo))
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.srv.server_address[1]}"

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        self.tmp.cleanup()

    def test_atender_get_reenvia_a_la_api(self):
        codigo, _, cuerpo = atender("GET /api/estado", self.url).partition(" ")
        self.assertEqual(codigo, "200")
        self.assertIn('"portatil-bt"', cuerpo)

    def test_rechaza_rutas_fuera_de_la_api(self):
        self.assertTrue(atender("GET /etc/passwd", self.url).startswith("400"))
        self.assertTrue(atender("DELETE /api/estado", self.url).startswith("400"))

    def test_post_con_error_devuelve_el_codigo_de_la_api(self):
        self.assertTrue(atender('POST /api/alertas/99 {"estado": "ATENDIDA"}', self.url).startswith("404"))

    def test_puente_responde_una_linea_por_peticion_aunque_lleguen_por_trozos(self):
        conexion = ConexionFalsa(b"GET /api/umbrales\nGET /api/procesos\n")
        puente = PuenteBluetooth("COM_PRUEBA", self.url, abrir=lambda _p: conexion)
        puente.start()
        for _ in range(60):
            if puente.atendidas >= 2:
                break
            time.sleep(0.05)
        puente.detener()
        lineas = conexion.salida.decode().strip().split("\n")
        self.assertEqual(len(lineas), 2)
        self.assertTrue(all(l.startswith("200 ") for l in lineas))

    @unittest.skipUnless(hasattr(os, "openpty"), "requiere pseudoterminal (Linux o macOS)")
    def test_puente_con_puerto_serie_real(self):
        maestro, esclavo = os.openpty()
        import tty
        tty.setraw(esclavo)
        puente = PuenteBluetooth(os.ttyname(esclavo), self.url)
        puente.start()
        try:
            time.sleep(0.3)
            os.write(maestro, b"GET /api/estado\n")
            recibido = b""
            limite = time.time() + 5
            while b"\n" not in recibido and time.time() < limite:
                recibido += os.read(maestro, 65536)
            self.assertTrue(recibido.startswith(b"200 "))
        finally:
            puente.detener()
            time.sleep(0.6)
            os.close(maestro)
            os.close(esclavo)


if __name__ == "__main__":
    unittest.main()
