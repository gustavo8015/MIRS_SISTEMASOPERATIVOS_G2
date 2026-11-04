"""Parámetros generales del prototipo."""
import sys
from pathlib import Path

# Empaquetado como MIRS_Servidor.exe (PyInstaller): los recursos vienen dentro del .exe
# y la base de datos se guarda junto al .exe para que no se pierda al cerrarlo.
EMPAQUETADO = getattr(sys, "frozen", False)

if EMPAQUETADO:
    RAIZ_PROYECTO = Path(sys.executable).resolve().parent
    RUTA_ESQUEMA = Path(getattr(sys, "_MEIPASS", RAIZ_PROYECTO)) / "database" / "schema.sql"
else:
    RAIZ_PROYECTO = Path(__file__).resolve().parents[2]
    RUTA_ESQUEMA = RAIZ_PROYECTO / "database" / "schema.sql"

RUTA_BD = RAIZ_PROYECTO / "datos" / "mirs.db"

# Segundos entre una muestra y la siguiente (el requerimiento pide 1 a 2 s).
INTERVALO_SEG = 2.0

# Cantidad de procesos de mayor consumo que se guardan en cada muestra.
TOP_PROCESOS = 10

# Puertos: API HTTP para la app y respuesta a la búsqueda de equipos (UDP).
PUERTO_API = 8765
PUERTO_DESCUBRIMIENTO = 8766
MENSAJE_BUSQUEDA = b"MIRS_BUSCAR"

# Umbrales iniciales: recurso -> (porcentaje límite, segundos sostenidos).
UMBRALES_POR_DEFECTO = {
    "CPU": (85.0, 60),
    "RAM": (90.0, 60),
    "DISCO": (90.0, 0),
}
