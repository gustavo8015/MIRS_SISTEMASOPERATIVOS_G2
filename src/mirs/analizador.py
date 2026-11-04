"""Motor de análisis: decide si la muestra más reciente es una anomalía.

Tres reglas, todas con estadística descriptiva (no hay modelos entrenados):
1. Umbral sostenido (ALTA): el recurso supera el límite durante todo el tiempo configurado.
2. Desviación del historial (MEDIA): el valor actual supera en más de tres desviaciones
   estándar el promedio de las muestras anteriores.
3. Posible fuga de memoria (MEDIA): la memoria de un mismo proceso no baja en sus últimas
   apariciones y crece al menos 50 % y 100 MB.

Las funciones de evaluación son puras (reciben listas y devuelven alertas) para poder probarlas
sin base de datos. `analizar()` reúne los datos, evita duplicados y guarda.
"""
from __future__ import annotations

from datetime import datetime
from statistics import mean, pstdev

CAMPO = {"CPU": "cpu_pct", "RAM": "ram_pct", "DISCO": "disco_pct"}
TIPO_UMBRAL = {"CPU": "PICO_CPU", "RAM": "RAM_ALTA", "DISCO": "DISCO_LLENO"}

VENTANA_HISTORIAL = 30       # muestras para el promedio y la desviación
FACTOR_DESVIACION = 3.0
VENTANA_FUGA = 10            # apariciones consecutivas del proceso
CRECIMIENTO_FUGA = 0.5       # 50 %
MINIMO_FUGA_MB = 100.0

RECOMENDACION = {
    "PICO_CPU": "Revise el proceso indicado; si no lo necesita, ciérrelo o baje su prioridad.",
    "RAM_ALTA": "Cierre programas o pestañas que no esté usando para liberar memoria.",
    "DISCO_LLENO": "Libere espacio o mueva archivos a otra unidad.",
    "POSIBLE_FUGA_MEMORIA": "Reinicie el programa indicado; si el crecimiento se repite, repórtelo al soporte.",
}


def _segundos(a: str, b: str) -> float:
    return (datetime.fromisoformat(b) - datetime.fromisoformat(a)).total_seconds()


def evaluar_umbrales(muestras: list[dict], umbrales: list[dict]) -> list[dict]:
    """Regla 1. `muestras` en orden cronológico; la última es la actual."""
    if not muestras:
        return []
    actual = muestras[-1]
    alertas = []
    for u in umbrales:
        campo = CAMPO.get(u["recurso"])
        if campo is None or actual[campo] <= u["valor_limite"]:
            continue
        # muestras dentro de la ventana [actual - duracion, actual]
        ventana = [m for m in muestras if _segundos(m["fecha_hora"], actual["fecha_hora"]) <= u["duracion_seg"]]
        # el historial debe cubrir la duración configurada (con 10 % de tolerancia por el muestreo)
        cubre = _segundos(ventana[0]["fecha_hora"], actual["fecha_hora"]) >= u["duracion_seg"] * 0.9
        if u["duracion_seg"] > 0 and not cubre:
            continue
        if all(m[campo] > u["valor_limite"] for m in ventana):
            tipo = TIPO_UMBRAL[u["recurso"]]
            alertas.append({
                "tipo": tipo, "severidad": "ALTA", "id_umbral": u["id_umbral"],
                "mensaje": (f"{u['recurso']} en {actual[campo]:.0f} %, sobre el umbral de "
                            f"{u['valor_limite']:.0f} %" + (f" durante {u['duracion_seg']} s." if u["duracion_seg"] else ".")),
                "recomendacion": RECOMENDACION[tipo],
            })
    return alertas


def evaluar_desviacion(muestras: list[dict]) -> list[dict]:
    """Regla 2. Compara la muestra actual con las VENTANA_HISTORIAL anteriores."""
    if len(muestras) < VENTANA_HISTORIAL + 1:
        return []
    actual, previas = muestras[-1], muestras[-VENTANA_HISTORIAL - 1:-1]
    alertas = []
    for recurso in ("CPU", "RAM"):
        campo = CAMPO[recurso]
        valores = [m[campo] for m in previas]
        promedio, desviacion = mean(valores), pstdev(valores)
        limite = promedio + FACTOR_DESVIACION * max(desviacion, 1.0)  # piso de 1 punto para series planas
        if actual[campo] > limite:
            tipo = TIPO_UMBRAL[recurso]
            alertas.append({
                "tipo": tipo, "severidad": "MEDIA",
                "mensaje": (f"Uso de {recurso} inusual para este equipo: {actual[campo]:.0f} % frente a un "
                            f"promedio de {promedio:.0f} % en las últimas {VENTANA_HISTORIAL} muestras."),
                "recomendacion": RECOMENDACION[tipo],
            })
    return alertas


def evaluar_fuga(proceso: dict, memorias: list[float]) -> dict | None:
    """Regla 3. `memorias`: valores del mismo proceso en orden cronológico, incluido el actual."""
    if len(memorias) < VENTANA_FUGA:
        return None
    serie = memorias[-VENTANA_FUGA:]
    no_baja = all(b >= a for a, b in zip(serie, serie[1:]))
    crecimiento = serie[-1] - serie[0]
    if no_baja and serie[0] > 0 and crecimiento >= MINIMO_FUGA_MB and crecimiento / serie[0] >= CRECIMIENTO_FUGA:
        return {
            "tipo": "POSIBLE_FUGA_MEMORIA", "severidad": "MEDIA",
            "pid": proceso["pid"], "nombre_proceso": proceso["nombre"],
            "mensaje": (f"{proceso['nombre']} (PID {proceso['pid']}) pasó de {serie[0]:.0f} a "
                        f"{serie[-1]:.0f} MB sin liberar memoria."),
            "recomendacion": RECOMENDACION["POSIBLE_FUGA_MEMORIA"],
        }
    return None


def analizar(bd, id_equipo: int, id_muestra: int) -> list[int]:
    """Aplica las tres reglas a la muestra recién guardada y guarda las alertas nuevas."""
    muestras = bd.muestras(id_equipo, 200)
    procesos = bd.procesos_de_muestra(id_muestra)
    candidatas = evaluar_umbrales(muestras, bd.umbrales(id_equipo)) + evaluar_desviacion(muestras)
    for p in procesos:
        fuga = evaluar_fuga(p, bd.historial_proceso(id_equipo, p["pid"], p["nombre"], VENTANA_FUGA))
        if fuga:
            candidatas.append(fuga)

    guardadas = []
    for a in candidatas:
        # el proceso de mayor consumo acompaña las alertas de CPU y RAM
        if a.get("pid") is None and procesos and a["tipo"] in ("PICO_CPU", "RAM_ALTA"):
            clave = "cpu_pct" if a["tipo"] == "PICO_CPU" else "memoria_mb"
            mayor = max(procesos, key=lambda p: p[clave])
            a["pid"], a["nombre_proceso"] = mayor["pid"], mayor["nombre"]
            a["mensaje"] += f" Proceso principal: {mayor['nombre']} (PID {mayor['pid']})."
        pid_clave = a["pid"] if a["tipo"] == "POSIBLE_FUGA_MEMORIA" else None
        if bd.hay_alerta_activa(id_equipo, a["tipo"], a["severidad"], pid_clave):
            continue  # ya hay una alerta activa equivalente: no se repite
        guardadas.append(bd.guardar_alerta({"id_muestra": id_muestra, **a}))
    return guardadas
