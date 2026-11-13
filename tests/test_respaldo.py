import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from mirs.almacenamiento import Almacenamiento  # noqa: E402
from mirs.servidor import main  # noqa: E402
from test_almacenamiento import EQUIPO, MUESTRA, PROCESOS  # noqa: E402


class PruebasRespaldo(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.bd = Almacenamiento(self.dir / "origen.db")
        self.bd.inicializar()
        id_equipo = self.bd.registrar_equipo(EQUIPO)
        self.bd.guardar_muestra(id_equipo, MUESTRA, PROCESOS)

    def tearDown(self):
        self.tmp.cleanup()

    def test_respaldo_copia_todas_las_filas_e_integridad_ok(self):
        destino = self.dir / "respaldos" / "copia.db"
        resultado = self.bd.respaldar(destino)
        self.assertTrue(destino.exists())
        self.assertEqual(resultado["integridad"], "ok")
        self.assertEqual(resultado["filas"]["muestra"], 1)
        self.assertEqual(resultado["filas"]["proceso_muestra"], 2)
        with sqlite3.connect(destino) as c:
            self.assertEqual(c.execute("SELECT COUNT(*) FROM proceso_muestra").fetchone()[0], 2)

    def test_respaldo_no_sobrescribe_un_archivo_existente(self):
        destino = self.dir / "copia.db"
        destino.write_text("no tocar")
        with self.assertRaises(FileExistsError):
            self.bd.respaldar(destino)
        self.assertEqual(destino.read_text(), "no tocar")

    def test_opcion_respaldo_del_servidor_termina_sin_iniciar_la_api(self):
        destino = self.dir / "cli.db"
        with self.assertRaises(SystemExit) as fin:
            main(["--bd", str(self.dir / "origen.db"), "--respaldo", str(destino)])
        self.assertEqual(fin.exception.code, 0)
        self.assertTrue(destino.exists())


if __name__ == "__main__":
    unittest.main()
