"""Punto de entrada de MIRS_Servidor.exe (empaquetado con PyInstaller).

Al ejecutar el .exe con doble clic, el servidor arranca en modo red (--lan): monitorea este PC,
publica la API en el puerto 8765 y responde a la búsqueda de equipos de la app MIRS Móvil.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))

from mirs import config  # noqa: E402
from mirs.servidor import main  # noqa: E402

if __name__ == "__main__":
    try:
        main(lan_por_defecto=True)
    except SystemExit as salida:
        if config.EMPAQUETADO and salida.code:
            input("Presione Enter para cerrar...")
        raise
    except Exception as error:  # que la ventana no se cierre sin mostrar el error
        print(f"Error: {error}")
        if config.EMPAQUETADO:
            input("Presione Enter para cerrar...")
        raise
