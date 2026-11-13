# Configuración del entorno de MIRS en Windows. La leen todos los scripts de scripts/windows.
@{
    Raiz            = 'C:\MIRS'                 # bin, datos, respaldos y logs
    Ejecutable      = 'MIRS_Servidor.exe'
    PuertoApi       = 8765                      # TCP, API JSON para la app móvil
    PuertoBusqueda  = 8766                      # UDP, respuesta a "Buscar equipos"
    NombreTarea     = 'MIRS-Servidor'           # tarea programada que mantiene el servidor activo
    TareaVigilante  = 'MIRS-Vigilante'          # comprueba la API cada 5 minutos y reinicia si no responde
    TareaRespaldo   = 'MIRS-Respaldo'           # copia diaria de la base de datos
    HoraRespaldo    = '23:30'
    DiasRetencion   = 7                         # respaldos que se conservan

    # Cuentas y grupos locales (principio de mínimo privilegio)
    GrupoOperadores = 'MIRS-Operadores'         # consultan datos y respaldos, no modifican la base
    GrupoAdmins     = 'MIRS-Administradores'    # administran la instalación
    UsuarioOperador = 'mirs_operador'
    UsuarioAdmin    = 'mirs_admin'

    # Política de contraseñas y bloqueo de cuentas locales
    LongitudMinima  = 12
    VigenciaDias    = 90
    IntentosBloqueo = 5
    MinutosBloqueo  = 15
}
