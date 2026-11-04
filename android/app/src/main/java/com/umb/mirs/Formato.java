package com.umb.mirs;

import java.util.Locale;

/** Formatos en español de Colombia: coma decimal y punto de miles. */
final class Formato {

    static final Locale ES = Locale.forLanguageTag("es-CO");

    private Formato() {
    }

    static String porcentaje(double valor) {
        return String.format(ES, "%.0f %%", valor);
    }

    static String decimal(double valor) {
        return String.format(ES, "%,.1f", valor);
    }

    static String entero(double valor) {
        return String.format(ES, "%,.0f", valor);
    }

    /** "2026-11-04T09:42:16" -> "09:42:16". */
    static String hora(String iso) {
        if (iso == null) {
            return "";
        }
        int t = iso.indexOf('T');
        if (t < 0) {
            t = iso.indexOf(' ');
        }
        return (t >= 0 && iso.length() >= t + 9) ? iso.substring(t + 1, t + 9) : iso;
    }

    static String tipoAlerta(String tipo) {
        switch (tipo) {
            case "PICO_CPU":
                return "Uso alto de CPU";
            case "RAM_ALTA":
                return "Memoria RAM alta";
            case "POSIBLE_FUGA_MEMORIA":
                return "Posible fuga de memoria";
            case "DISCO_LLENO":
                return "Disco casi lleno";
            case "RED_SATURADA":
                return "Red saturada";
            case "PROCESO_SIN_RESPUESTA":
                return "Proceso sin respuesta";
            default:
                return tipo;
        }
    }

    static String estadoProceso(String estado) {
        switch (estado) {
            case "running":
                return "en ejecución";
            case "sleeping":
                return "en espera";
            case "stopped":
                return "detenido";
            case "zombie":
                return "zombi";
            case "disk-sleep":
                return "esperando E/S";
            default:
                return estado;
        }
    }
}
