"""Prueba de consola del recolector y del almacenamiento.

Uso:  python -m mirs --muestras 5
(ejecutar desde la carpeta src, o con PYTHONPATH=src)
"""
import argparse

from . import config
from .almacenamiento import Almacenamiento
from .recolector import datos_equipo, listar_procesos, tomar_muestra


def main() -> None:
    parser = argparse.ArgumentParser(description="MIRS: toma muestras y las guarda en SQLite.")
    parser.add_argument("--muestras", type=int, default=5)
    parser.add_argument("--intervalo", type=float, default=config.INTERVALO_SEG)
    parser.add_argument("--bd", default=str(config.RUTA_BD))
    args = parser.parse_args()

    bd = Almacenamiento(args.bd)
    bd.inicializar()
    equipo = datos_equipo()
    id_equipo = bd.registrar_equipo(equipo)
    bd.crear_umbrales_por_defecto(id_equipo)
    print(f"Equipo {equipo['nombre_host']} ({equipo['sistema_operativo']}), "
          f"{equipo['nucleos_cpu']} núcleos, {equipo['ram_total_mb']} MB de RAM")
    print(f"{'Hora':<20}{'CPU %':>7}{'RAM %':>7}{'Disco %':>9}  Proceso de mayor consumo")

    for _ in range(args.muestras):
        muestra = tomar_muestra(args.intervalo)
        procesos = listar_procesos(config.TOP_PROCESOS)
        bd.guardar_muestra(id_equipo, muestra, procesos)
        mayor = procesos[0] if procesos else {"nombre": "-", "pid": "-", "cpu_pct": 0}
        print(f"{muestra['fecha_hora']:<20}{muestra['cpu_pct']:>7.1f}{muestra['ram_pct']:>7.1f}"
              f"{muestra['disco_pct']:>9.1f}  {mayor['nombre']} (PID {mayor['pid']}, {mayor['cpu_pct']} %)")
    print(f"Muestras guardadas en {args.bd}")


if __name__ == "__main__":
    main()
