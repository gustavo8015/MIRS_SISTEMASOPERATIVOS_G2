"""Parámetros generales del prototipo."""
from pathlib import Path

RAIZ_PROYECTO = Path(__file__).resolve().parents[2]
RUTA_ESQUEMA = RAIZ_PROYECTO / "database" / "schema.sql"
RUTA_BD = RAIZ_PROYECTO / "datos" / "mirs.db"

# Segundos entre una muestra y la siguiente (el requerimiento pide 1 a 2 s).
INTERVALO_SEG = 2.0

# Cantidad de procesos de mayor consumo que se guardan en cada muestra.
TOP_PROCESOS = 10

# Umbrales iniciales: recurso -> (porcentaje límite, segundos sostenidos).
UMBRALES_POR_DEFECTO = {
    "CPU": (85.0, 60),
    "RAM": (90.0, 60),
    "DISCO": (90.0, 0),
}
