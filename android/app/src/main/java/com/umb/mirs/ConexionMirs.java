package com.umb.mirs;

import java.io.IOException;

/**
 * Forma de hablar con el servidor de MIRS. La app no sabe si las peticiones viajan por la red
 * (ApiCliente, HTTP) o por Bluetooth (ClienteBluetooth, RFCOMM): ambas responden el mismo JSON.
 */
public interface ConexionMirs {

    String get(String ruta) throws IOException;

    String post(String ruta, String json) throws IOException;

    /** Texto para mostrar al usuario: la URL o el equipo Bluetooth. */
    String getBase();

    default void cerrar() {
    }
}
