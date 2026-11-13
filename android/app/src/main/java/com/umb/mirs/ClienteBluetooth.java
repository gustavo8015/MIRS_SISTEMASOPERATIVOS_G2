package com.umb.mirs;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Conexión con el servidor de MIRS por Bluetooth clásico (RFCOMM, perfil de puerto serie SPP).
 *
 * En el portátil, Windows publica el servicio SPP como un "puerto COM entrante" y el servidor
 * de MIRS lo atiende con --bluetooth COMx. Cada petición es una línea ("GET /api/estado") y
 * cada respuesta otra línea ("200 {json}").
 *
 * BluetoothSocket no tiene tiempo límite de lectura: un vigilante cierra el socket si una
 * respuesta tarda más de 8 s, para que la consulta periódica no quede bloqueada.
 */
@SuppressLint("MissingPermission") // MainActivity pide BLUETOOTH_CONNECT antes de crear esta clase
final class ClienteBluetooth implements ConexionMirs {

    /** UUID estándar del perfil de puerto serie (SPP). */
    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothDevice equipo;
    private final String nombre;
    private final ScheduledExecutorService vigilante = Executors.newSingleThreadScheduledExecutor();
    private volatile BluetoothSocket socket;
    private BufferedReader lector;
    private OutputStream salida;

    ClienteBluetooth(BluetoothDevice equipo, String nombre) {
        this.equipo = equipo;
        this.nombre = nombre;
    }

    @Override
    public String getBase() {
        return "Bluetooth · " + nombre;
    }

    @Override
    public synchronized String get(String ruta) throws IOException {
        return pedir("GET " + ruta);
    }

    @Override
    public synchronized String post(String ruta, String json) throws IOException {
        return pedir("POST " + ruta + " " + json.replace('\n', ' '));
    }

    private String pedir(String linea) throws IOException {
        ScheduledFuture<?> limite = vigilante.schedule(this::cerrarSocket, 8, TimeUnit.SECONDS);
        try {
            if (socket == null || !socket.isConnected()) {
                BluetoothSocket nuevo = equipo.createRfcommSocketToServiceRecord(SPP);
                socket = nuevo;
                nuevo.connect();
                lector = new BufferedReader(new InputStreamReader(nuevo.getInputStream(), StandardCharsets.UTF_8));
                salida = nuevo.getOutputStream();
            }
            salida.write((linea + "\n").getBytes(StandardCharsets.UTF_8));
            salida.flush();
            String respuesta = lector.readLine();
            if (respuesta == null) {
                throw new IOException("El portátil cerró la conexión");
            }
            int espacio = respuesta.indexOf(' ');
            if (espacio < 0) {
                throw new IOException("Respuesta inválida: " + respuesta);
            }
            int codigo = Integer.parseInt(respuesta.substring(0, espacio));
            String cuerpo = respuesta.substring(espacio + 1);
            if (codigo >= 400) {
                throw new IOException("HTTP " + codigo + ": " + cuerpo);
            }
            return cuerpo;
        } catch (IOException | RuntimeException error) {
            cerrarSocket();
            throw error instanceof IOException ? (IOException) error : new IOException(error);
        } finally {
            limite.cancel(false);
        }
    }

    /** Sin synchronized: el vigilante debe poder cerrar el socket mientras otra petición espera. */
    private void cerrarSocket() {
        BluetoothSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignorada) {
                // ya estaba cerrado
            }
        }
    }

    @Override
    public void cerrar() {
        cerrarSocket();
        vigilante.shutdownNow();
    }
}
