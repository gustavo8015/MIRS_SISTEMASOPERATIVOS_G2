"""Punto de entrada de MIRS_Servidor.exe (empaquetado con PyInstaller).

Al ejecutar el .exe con doble clic, el servidor arranca en modo red (--lan) y con Bluetooth
automático: monitorea este PC, publica la API en el puerto 8765, responde a la búsqueda de equipos
de la app MIRS Móvil y atiende por Bluetooth en el puerto COM entrante que encuentre solo.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))

from mirs import config  # noqa: E402
from mirs.servidor import main  # noqa: E402


def _pausar() -> None:
    """Deja leer el error con doble clic; en tareas programadas (sin consola) no se detiene."""
    if config.EMPAQUETADO and sys.stdin is not None and sys.stdin.isatty():
        input("Presione Enter para cerrar...")


if __name__ == "__main__":
    try:
        main(lan_por_defecto=True, bluetooth_por_defecto="auto")
    except SystemExit as salida:
        if salida.code:
            _pausar()
        raise
    except Exception as error:  # que la ventana no se cierre sin mostrar el error
        print(f"Error: {error}")
        _pausar()
        raise
