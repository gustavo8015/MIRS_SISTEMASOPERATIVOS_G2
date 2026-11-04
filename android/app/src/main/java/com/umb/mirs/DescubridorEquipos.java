package com.umb.mirs;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Busca servidores de MIRS sin que el usuario escriba la IP.
 *
 * 1. Prueba http://10.0.2.2:8765, la dirección con la que el emulador llega al PC donde corre.
 * 2. Envía por difusión UDP (broadcast) el mensaje MIRS_BUSCAR al puerto 8766 de toda la red WiFi
 *    y recoge las respuestas de los PC que tengan el servidor abierto en modo red (--lan o el .exe).
 *
 * Se ejecuta en un hilo en segundo plano.
 */
final class DescubridorEquipos {

    static final int PUERTO_DESCUBRIMIENTO = 8766;
    static final String URL_EMULADOR = "http://10.0.2.2:8765";

    static final class Equipo {
        final String nombre;
        final String sistema;
        final String url;

        Equipo(String nombre, String sistema, String url) {
            this.nombre = nombre;
            this.sistema = sistema;
            this.url = url;
        }

        String etiqueta() {
            return nombre + " · " + sistema + "\n" + url;
        }
    }

    private DescubridorEquipos() {
    }

    static List<Equipo> buscar(int esperaMs) {
        Map<String, Equipo> encontrados = new LinkedHashMap<>();

        try {
            JSONObject estado = new JSONObject(new ApiCliente(URL_EMULADOR).get("/api/estado"));
            JSONObject equipo = estado.optJSONObject("equipo");
            if (equipo != null) {
                encontrados.put(URL_EMULADOR, new Equipo(equipo.optString("nombre_host"),
                        equipo.optString("sistema_operativo") + " (PC de este emulador)", URL_EMULADOR));
            }
        } catch (Exception ignorada) {
            // no es un emulador o el PC no tiene el servidor abierto
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(300);
            byte[] mensaje = "MIRS_BUSCAR".getBytes(StandardCharsets.UTF_8);
            InetAddress difusion = InetAddress.getByName("255.255.255.255");
            // se envía dos veces por si se pierde un paquete
            for (int i = 0; i < 2; i++) {
                socket.send(new DatagramPacket(mensaje, mensaje.length, difusion, PUERTO_DESCUBRIMIENTO));
            }
            long fin = System.currentTimeMillis() + esperaMs;
            byte[] bufer = new byte[1024];
            while (System.currentTimeMillis() < fin) {
                DatagramPacket respuesta = new DatagramPacket(bufer, bufer.length);
                try {
                    socket.receive(respuesta);
                } catch (SocketTimeoutException sinRespuesta) {
                    continue;
                }
                try {
                    String texto = new String(respuesta.getData(), 0, respuesta.getLength(), StandardCharsets.UTF_8);
                    JSONObject datos = new JSONObject(texto);
                    if (!"MIRS".equals(datos.optString("servicio"))) {
                        continue;
                    }
                    String url = "http://" + respuesta.getAddress().getHostAddress() + ":"
                            + datos.optInt("puerto", 8765);
                    if (!encontrados.containsKey(url)) {
                        encontrados.put(url, new Equipo(datos.optString("nombre_host"),
                                datos.optString("sistema_operativo"), url));
                    }
                } catch (Exception respuestaInvalida) {
                    // un paquete que no es de MIRS: se ignora
                }
            }
        } catch (Exception redNoDisponible) {
            // sin WiFi o la red no permite difusión: se devuelve lo que se haya encontrado
        }
        return new ArrayList<>(encontrados.values());
    }
}
