package com.umb.mirs;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Tarjetas de alertas. La severidad se indica con texto y con color, no solo con color. */
public class AlertaAdapter extends RecyclerView.Adapter<AlertaAdapter.Fila> {

    public interface AlCambiarEstado {
        void cambiar(int idAlerta, String estado);
    }

    private final List<JSONObject> alertas = new ArrayList<>();
    private final AlCambiarEstado accion;

    public AlertaAdapter(AlCambiarEstado accion) {
        this.accion = accion;
    }

    public void setDatos(JSONArray datos) {
        alertas.clear();
        for (int i = 0; i < datos.length(); i++) {
            JSONObject a = datos.optJSONObject(i);
            if (a != null) {
                alertas.add(a);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Fila onCreateViewHolder(@NonNull ViewGroup padre, int tipo) {
        return new Fila(LayoutInflater.from(padre.getContext()).inflate(R.layout.item_alerta, padre, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Fila f, int posicion) {
        JSONObject a = alertas.get(posicion);
        Context ctx = f.itemView.getContext();
        String severidad = a.optString("severidad");
        String estado = a.optString("estado");
        boolean activa = "ACTIVA".equals(estado);

        int franja, fondo, letra;
        if ("ALTA".equals(severidad)) {
            franja = R.color.peligro; fondo = R.color.fondo_peligro; letra = R.color.texto_peligro;
        } else if ("MEDIA".equals(severidad)) {
            franja = R.color.acento; fondo = R.color.fondo_aviso; letra = R.color.texto_aviso;
        } else {
            franja = R.color.primario; fondo = R.color.pista; letra = R.color.texto_suave;
        }
        if (!activa) {
            franja = R.color.borde; fondo = R.color.pista; letra = R.color.texto_suave;
        }
        f.franja.setBackgroundColor(ContextCompat.getColor(ctx, franja));
        f.severidad.setText(activa ? severidad : estado);
        f.severidad.setTextColor(ContextCompat.getColor(ctx, letra));
        f.severidad.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, fondo)));
        f.titulo.setText(Formato.tipoAlerta(a.optString("tipo")));
        f.hora.setText(Formato.hora(a.optString("fecha_hora")));
        f.mensaje.setText(a.optString("mensaje"));
        String recomendacion = a.optString("recomendacion", "");
        f.recomendacion.setVisibility(recomendacion.isEmpty() || "null".equals(recomendacion) ? View.GONE : View.VISIBLE);
        f.recomendacion.setText("Recomendación: " + recomendacion);
        f.itemView.setAlpha(activa ? 1f : 0.65f);
        f.acciones.setVisibility(activa ? View.VISIBLE : View.GONE);

        int id = a.optInt("id_alerta");
        f.atendida.setOnClickListener(v -> accion.cambiar(id, "ATENDIDA"));
        f.descartar.setOnClickListener(v -> accion.cambiar(id, "DESCARTADA"));
    }

    @Override
    public int getItemCount() {
        return alertas.size();
    }

    static class Fila extends RecyclerView.ViewHolder {
        final View franja, acciones, atendida, descartar;
        final TextView severidad, titulo, hora, mensaje, recomendacion;

        Fila(@NonNull View v) {
            super(v);
            franja = v.findViewById(R.id.franja);
            acciones = v.findViewById(R.id.acciones);
            atendida = v.findViewById(R.id.btn_atendida);
            descartar = v.findViewById(R.id.btn_descartar);
            severidad = v.findViewById(R.id.severidad);
            titulo = v.findViewById(R.id.titulo);
            hora = v.findViewById(R.id.hora);
            mensaje = v.findViewById(R.id.mensaje);
            recomendacion = v.findViewById(R.id.recomendacion);
        }
    }
}
