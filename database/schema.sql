-- MIRS: Monitor Inteligente de Recursos del Sistema
-- Esquema de la base de datos local (SQLite 3).
-- Seis tablas: equipo, umbral, muestra, proceso_muestra, alerta y accion.
-- Las llaves foráneas se activan en cada conexión con PRAGMA foreign_keys = ON.

PRAGMA foreign_keys = ON;

-- Equipo monitoreado. El prototipo trabaja con un solo equipo local,
-- pero la tabla deja abierta la posibilidad de registrar otros.
CREATE TABLE IF NOT EXISTS equipo (
    id_equipo          INTEGER PRIMARY KEY AUTOINCREMENT,
    nombre_host        TEXT    NOT NULL UNIQUE,
    sistema_operativo  TEXT    NOT NULL,
    version_so         TEXT,
    nucleos_cpu        INTEGER NOT NULL CHECK (nucleos_cpu > 0),
    ram_total_mb       INTEGER NOT NULL CHECK (ram_total_mb > 0),
    disco_total_gb     REAL    NOT NULL CHECK (disco_total_gb > 0),
    fecha_registro     TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- Límite configurable por recurso. Un equipo tiene como máximo un umbral por recurso.
CREATE TABLE IF NOT EXISTS umbral (
    id_umbral     INTEGER PRIMARY KEY AUTOINCREMENT,
    id_equipo     INTEGER NOT NULL REFERENCES equipo (id_equipo) ON DELETE CASCADE,
    recurso       TEXT    NOT NULL CHECK (recurso IN ('CPU', 'RAM', 'DISCO', 'RED')),
    valor_limite  REAL    NOT NULL CHECK (valor_limite > 0),
    duracion_seg  INTEGER NOT NULL DEFAULT 60 CHECK (duracion_seg >= 0),
    activo        INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    UNIQUE (id_equipo, recurso)
);

-- Lectura periódica del uso global de recursos (cada 1 a 2 segundos).
CREATE TABLE IF NOT EXISTS muestra (
    id_muestra          INTEGER PRIMARY KEY AUTOINCREMENT,
    id_equipo           INTEGER NOT NULL REFERENCES equipo (id_equipo) ON DELETE CASCADE,
    fecha_hora          TEXT    NOT NULL,
    cpu_pct             REAL    NOT NULL CHECK (cpu_pct BETWEEN 0 AND 100),
    ram_pct             REAL    NOT NULL CHECK (ram_pct BETWEEN 0 AND 100),
    disco_pct           REAL    NOT NULL CHECK (disco_pct BETWEEN 0 AND 100),
    red_envio_kbps      REAL    NOT NULL DEFAULT 0 CHECK (red_envio_kbps >= 0),
    red_recepcion_kbps  REAL    NOT NULL DEFAULT 0 CHECK (red_recepcion_kbps >= 0)
);
CREATE INDEX IF NOT EXISTS ix_muestra_equipo_fecha ON muestra (id_equipo, fecha_hora);

-- Procesos de mayor consumo observados en cada muestra (los N primeros).
CREATE TABLE IF NOT EXISTS proceso_muestra (
    id_proceso_muestra  INTEGER PRIMARY KEY AUTOINCREMENT,
    id_muestra          INTEGER NOT NULL REFERENCES muestra (id_muestra) ON DELETE CASCADE,
    pid                 INTEGER NOT NULL CHECK (pid >= 0),
    nombre              TEXT    NOT NULL,
    estado              TEXT,
    prioridad           INTEGER,
    cpu_pct             REAL    NOT NULL DEFAULT 0 CHECK (cpu_pct >= 0),
    memoria_mb          REAL    NOT NULL DEFAULT 0 CHECK (memoria_mb >= 0),
    hilos               INTEGER CHECK (hilos IS NULL OR hilos >= 0)
);
CREATE INDEX IF NOT EXISTS ix_proceso_muestra ON proceso_muestra (id_muestra);

-- Evento anómalo detectado. id_umbral queda vacío cuando la anomalía
-- se detecta por análisis estadístico del historial y no por un umbral fijo.
CREATE TABLE IF NOT EXISTS alerta (
    id_alerta       INTEGER PRIMARY KEY AUTOINCREMENT,
    id_muestra      INTEGER NOT NULL REFERENCES muestra (id_muestra) ON DELETE CASCADE,
    id_umbral       INTEGER REFERENCES umbral (id_umbral) ON DELETE SET NULL,
    tipo            TEXT    NOT NULL CHECK (tipo IN ('PICO_CPU', 'RAM_ALTA', 'POSIBLE_FUGA_MEMORIA',
                                                     'DISCO_LLENO', 'RED_SATURADA', 'PROCESO_SIN_RESPUESTA')),
    severidad       TEXT    NOT NULL CHECK (severidad IN ('BAJA', 'MEDIA', 'ALTA')),
    pid             INTEGER,
    nombre_proceso  TEXT,
    mensaje         TEXT    NOT NULL,
    recomendacion   TEXT,
    estado          TEXT    NOT NULL DEFAULT 'ACTIVA' CHECK (estado IN ('ACTIVA', 'ATENDIDA', 'DESCARTADA'))
);
CREATE INDEX IF NOT EXISTS ix_alerta_estado ON alerta (estado);

-- Acción que el usuario confirma sobre un proceso. Puede nacer de una alerta
-- o directamente desde la lista de procesos (id_alerta vacío).
CREATE TABLE IF NOT EXISTS accion (
    id_accion       INTEGER PRIMARY KEY AUTOINCREMENT,
    id_equipo       INTEGER NOT NULL REFERENCES equipo (id_equipo) ON DELETE CASCADE,
    id_alerta       INTEGER REFERENCES alerta (id_alerta) ON DELETE SET NULL,
    pid             INTEGER NOT NULL,
    nombre_proceso  TEXT    NOT NULL,
    tipo            TEXT    NOT NULL CHECK (tipo IN ('FINALIZAR', 'CAMBIAR_PRIORIDAD')),
    detalle         TEXT,
    confirmada      INTEGER NOT NULL CHECK (confirmada IN (0, 1)),
    resultado       TEXT    NOT NULL CHECK (resultado IN ('EXITOSA', 'DENEGADA', 'FALLIDA', 'CANCELADA')),
    fecha_hora      TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
);
