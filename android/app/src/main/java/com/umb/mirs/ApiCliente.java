package com.umb.mirs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Cliente HTTP mínimo para la API del servidor de MIRS en el PC.
 * Se llama siempre desde un hilo en segundo plano: Android prohíbe la red en el hilo principal.
 */
public final class ApiCliente {

    private final String base;

    public ApiCliente(String base) {
        String limpia = base.trim();
        while (limpia.endsWith("/")) {
            limpia = limpia.substring(0, limpia.length() - 1);
        }
        this.base = limpia;
    }

    public String getBase() {
        return base;
    }

    public String get(String ruta) throws IOException {
        return pedir("GET", ruta, null);
    }

    public String post(String ruta, String json) throws IOException {
        return pedir("POST", ruta, json);
    }

    private String pedir(String metodo, String ruta, String cuerpo) throws IOException {
        HttpURLConnection conexion = (HttpURLConnection) URI.create(base + ruta).toURL().openConnection();
        conexion.setRequestMethod(metodo);
        conexion.setConnectTimeout(3000);
        conexion.setReadTimeout(5000);
        conexion.setRequestProperty("Accept", "application/json");
        try {
            if (cuerpo != null) {
                conexion.setDoOutput(true);
                conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream salida = conexion.getOutputStream()) {
                    salida.write(cuerpo.getBytes(StandardCharsets.UTF_8));
                }
            }
            int codigo = conexion.getResponseCode();
            String texto = leer(codigo >= 400 ? conexion.getErrorStream() : conexion.getInputStream());
            if (codigo >= 400) {
                throw new IOException("HTTP " + codigo + ": " + texto);
            }
            return texto;
        } finally {
            conexion.disconnect();
        }
    }

    private static String leer(InputStream entrada) throws IOException {
        if (entrada == null) {
            return "";
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] bufer = new byte[8192];
        int leidos;
        try (InputStream in = entrada) {
            while ((leidos = in.read(bufer)) != -1) {
                bytes.write(bufer, 0, leidos);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
