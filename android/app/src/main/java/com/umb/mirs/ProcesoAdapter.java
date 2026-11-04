package com.umb.mirs;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Lista de procesos de la última muestra, con filtro por nombre o PID. */
public class ProcesoAdapter extends RecyclerView.Adapter<ProcesoAdapter.Fila> {

    private final List<JSONObject> todos = new ArrayList<>();
    private final List<JSONObject> visibles = new ArrayList<>();
    private String filtro = "";

    public void setDatos(JSONArray procesos) {
        todos.clear();
        for (int i = 0; i < procesos.length(); i++) {
            JSONObject p = procesos.optJSONObject(i);
            if (p != null) {
                todos.add(p);
            }
        }
        aplicarFiltro();
    }

    public void setFiltro(String texto) {
        filtro = texto.trim().toLowerCase(Formato.ES);
        aplicarFiltro();
    }

    private void aplicarFiltro() {
        visibles.clear();
        for (JSONObject p : todos) {
            String nombre = p.optString("nombre").toLowerCase(Formato.ES);
            String pid = String.valueOf(p.optInt("pid"));
            if (filtro.isEmpty() || nombre.contains(filtro) || pid.startsWith(filtro)) {
                visibles.add(p);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Fila onCreateViewHolder(@NonNull ViewGroup padre, int tipo) {
        View v = LayoutInflater.from(padre.getContext()).inflate(R.layout.item_proceso, padre, false);
        return new Fila(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Fila fila, int posicion) {
        llenar(fila.itemView, visibles.get(posicion));
    }

    @Override
    public int getItemCount() {
        return visibles.size();
    }

    /** También se usa en la tarjeta "Mayor consumo" del panel. */
    static void llenar(View fila, JSONObject p) {
        ((TextView) fila.findViewById(R.id.pid)).setText(String.valueOf(p.optInt("pid")));
        ((TextView) fila.findViewById(R.id.nombre)).setText(p.optString("nombre"));
        String detalle = Formato.estadoProceso(p.optString("estado"))
                + " · prioridad " + p.optString("prioridad", "-")
                + " · " + p.optInt("hilos") + " hilos";
        ((TextView) fila.findViewById(R.id.detalle)).setText(detalle);
        ((TextView) fila.findViewById(R.id.cpu)).setText(Formato.decimal(p.optDouble("cpu_pct", 0)));
        ((TextView) fila.findViewById(R.id.ram)).setText(Formato.decimal(p.optDouble("memoria_mb", 0)));
    }

    static class Fila extends RecyclerView.ViewHolder {
        Fila(@NonNull View v) {
            super(v);
        }
    }
}
