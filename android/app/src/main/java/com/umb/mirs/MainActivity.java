package com.umb.mirs;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pantalla principal de MIRS Móvil. Consulta cada 2 segundos la API del servidor de MIRS
 * que corre en el PC y muestra el panel, los procesos y las alertas.
 *
 * La red se usa en un hilo aparte (ExecutorService) y los resultados se pintan en el hilo
 * principal (Handler): el mismo principio que la aplicación del PC aplica con Tkinter.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFERENCIAS = "mirs";
    private static final String CLAVE_SERVIDOR = "servidor";
    /** 10.0.2.2 es la dirección con la que el emulador de Android Studio llega al PC. */
    private static final String SERVIDOR_POR_DEFECTO = "http://10.0.2.2:8765";
    private static final long INTERVALO_MS = 2000;

    private final ExecutorService red = Executors.newSingleThreadExecutor();
    private final Handler principal = new Handler(Looper.getMainLooper());
    private ApiCliente api;
    private boolean consultando = false;
    private int pestana = R.id.nav_panel;
    private boolean soloActivas = true;

    private MaterialToolbar barra;
    private TextView estadoConexion;
    private View vistaPanel, vistaProcesos, vistaAlertas;
    private BottomNavigationView navegacion;
    private SwipeRefreshLayout refrescar;
    private GraficaLineas grafica;
    private TextView leyenda, textoBanner, sinAlertas;
    private MaterialCardView banner;
    private LinearLayout listaMayorConsumo;
    private final ProcesoAdapter procesosAdapter = new ProcesoAdapter();
    private AlertaAdapter alertasAdapter;

    private final Runnable ciclo = new Runnable() {
        @Override
        public void run() {
            actualizar();
            principal.postDelayed(this, INTERVALO_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences prefs = getSharedPreferences(PREFERENCIAS, MODE_PRIVATE);
        api = new ApiCliente(prefs.getString(CLAVE_SERVIDOR, SERVIDOR_POR_DEFECTO));

        barra = findViewById(R.id.barra);
        barra.inflateMenu(R.menu.barra);
        barra.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.accion_servidor) {
                dialogoServidor();
                return true;
            }
            return false;
        });

        estadoConexion = findViewById(R.id.estado_conexion);
        vistaPanel = findViewById(R.id.vista_panel);
        vistaProcesos = findViewById(R.id.vista_procesos);
        vistaAlertas = findViewById(R.id.vista_alertas);
        // El <include> de activity_main le da a la raíz de vista_panel el id vista_panel,
        // así que el SwipeRefreshLayout se busca con ese id (refrescar_panel deja de existir).
        refrescar = findViewById(R.id.vista_panel);
        grafica = findViewById(R.id.grafica);
        leyenda = findViewById(R.id.leyenda);
        banner = findViewById(R.id.banner_alerta);
        textoBanner = findViewById(R.id.texto_banner);
        listaMayorConsumo = findViewById(R.id.lista_mayor_consumo);
        sinAlertas = findViewById(R.id.sin_alertas);

        navegacion = findViewById(R.id.navegacion);
        navegacion.setOnItemSelectedListener(item -> {
            mostrarPestana(item.getItemId());
            return true;
        });

        refrescar.setOnRefreshListener(this::actualizar);
        banner.setOnClickListener(v -> navegacion.setSelectedItemId(R.id.nav_alertas));

        RecyclerView listaProcesos = findViewById(R.id.lista_procesos);
        listaProcesos.setLayoutManager(new LinearLayoutManager(this));
        listaProcesos.setAdapter(procesosAdapter);

        alertasAdapter = new AlertaAdapter(this::cambiarEstadoAlerta);
        RecyclerView listaAlertas = findViewById(R.id.lista_alertas);
        listaAlertas.setLayoutManager(new LinearLayoutManager(this));
        listaAlertas.setAdapter(alertasAdapter);

        EditText buscar = findViewById(R.id.buscar);
        buscar.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                procesosAdapter.setFiltro(s.toString());
            }
        });

        MaterialButtonToggleGroup filtro = findViewById(R.id.filtro_alertas);
        filtro.addOnButtonCheckedListener((grupo, idBoton, marcado) -> {
            if (marcado) {
                soloActivas = idBoton == R.id.btn_activas;
                actualizar();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        principal.post(ciclo);
    }

    @Override
    protected void onPause() {
        super.onPause();
        principal.removeCallbacks(ciclo);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        red.shutdownNow();
    }

    private void mostrarPestana(int id) {
        pestana = id;
        vistaPanel.setVisibility(id == R.id.nav_panel ? View.VISIBLE : View.GONE);
        vistaProcesos.setVisibility(id == R.id.nav_procesos ? View.VISIBLE : View.GONE);
        vistaAlertas.setVisibility(id == R.id.nav_alertas ? View.VISIBLE : View.GONE);
        actualizar();
    }

    /** Pide a la API lo que necesita la pestaña visible. Nunca hay dos consultas a la vez. */
    private void actualizar() {
        if (consultando) {
            return;
        }
        consultando = true;
        final int tab = pestana;
        final boolean activas = soloActivas;
        final ApiCliente cliente = api;
        red.execute(() -> {
            try {
                JSONObject estado = new JSONObject(cliente.get("/api/estado"));
                JSONArray alertasActivas = new JSONArray(cliente.get("/api/alertas?estado=ACTIVA"));
                JSONArray muestras = tab == R.id.nav_panel
                        ? new JSONArray(cliente.get("/api/muestras?cantidad=150")) : null;
                JSONArray umbrales = tab == R.id.nav_panel
                        ? new JSONArray(cliente.get("/api/umbrales")) : null;
                JSONArray procesos = tab == R.id.nav_procesos
                        ? new JSONArray(cliente.get("/api/procesos")) : null;
                JSONArray alertas = tab == R.id.nav_alertas
                        ? (activas ? alertasActivas : new JSONArray(cliente.get("/api/alertas?estado=TODAS")))
                        : null;
                principal.post(() -> {
                    consultando = false;
                    refrescar.setRefreshing(false);
                    pintar(estado, alertasActivas, muestras, umbrales, procesos, alertas);
                });
            } catch (Exception e) {
                principal.post(() -> {
                    consultando = false;
                    refrescar.setRefreshing(false);
                    estadoConexion.setText(getString(R.string.sin_conexion, cliente.getBase()));
                    estadoConexion.setTextColor(ContextCompat.getColor(this, R.color.peligro));
                });
            }
        });
    }

    private void pintar(JSONObject estado, JSONArray alertasActivas, JSONArray muestras,
                        JSONArray umbrales, JSONArray procesos, JSONArray alertas) {
        JSONObject equipo = estado.optJSONObject("equipo");
        JSONObject muestra = estado.optJSONObject("muestra");
        if (equipo != null) {
            barra.setSubtitle(equipo.optString("nombre_host") + " · " + equipo.optString("sistema_operativo"));
        }
        estadoConexion.setTextColor(ContextCompat.getColor(this, R.color.texto_suave));
        estadoConexion.setText(muestra == null
                ? getString(R.string.conectando)
                : getString(R.string.conectado, Formato.hora(muestra.optString("fecha_hora"))));

        int activas = alertasActivas.length();
        BadgeDrawable insignia = navegacion.getOrCreateBadge(R.id.nav_alertas);
        insignia.setNumber(activas);
        insignia.setVisible(activas > 0);

        if (muestra != null && equipo != null && umbrales != null) {
            pintarPanel(estado, equipo, muestra, umbrales, alertasActivas);
        }
        if (muestras != null) {
            float limiteCpu = (float) limite(umbrales, "CPU", 85);
            float[] cpu = new float[muestras.length()];
            float[] ram = new float[muestras.length()];
            for (int i = 0; i < muestras.length(); i++) {
                JSONObject m = muestras.optJSONObject(i);
                cpu[i] = (float) m.optDouble("cpu_pct", 0);
                ram[i] = (float) m.optDouble("ram_pct", 0);
            }
            grafica.setDatos(cpu, ram, limiteCpu);
            double segundos = muestras.length() * estado.optDouble("intervalo_seg", 2);
            leyenda.setText("CPU azul · RAM naranja · " + Formato.entero(Math.ceil(segundos / 60)) + " min");
        }
        if (procesos != null) {
            procesosAdapter.setDatos(procesos);
        }
        if (alertas != null) {
            alertasAdapter.setDatos(alertas);
            sinAlertas.setVisibility(alertas.length() == 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void pintarPanel(JSONObject estado, JSONObject equipo, JSONObject muestra,
                             JSONArray umbrales, JSONArray alertasActivas) {
        double cpu = muestra.optDouble("cpu_pct", 0);
        double ram = muestra.optDouble("ram_pct", 0);
        double disco = muestra.optDouble("disco_pct", 0);
        double limCpu = limite(umbrales, "CPU", 85);
        double limRam = limite(umbrales, "RAM", 90);
        double limDisco = limite(umbrales, "DISCO", 90);

        indicador(R.id.cpu_valor, R.id.cpu_barra, R.id.cpu_etiqueta, R.id.cpu_detalle, cpu, limCpu, equipo.optInt("nucleos_cpu") + " núcleos · umbral "
                + Formato.porcentaje(limCpu));
        double ramTotalGb = equipo.optDouble("ram_total_mb", 0) / 1024.0;
        indicador(R.id.ram_valor, R.id.ram_barra, R.id.ram_etiqueta, R.id.ram_detalle, ram, limRam, Formato.decimal(ramTotalGb * ram / 100) + " de "
                + Formato.decimal(ramTotalGb) + " GB");
        double discoTotal = equipo.optDouble("disco_total_gb", 0);
        indicador(R.id.disco_valor, R.id.disco_barra, R.id.disco_etiqueta, R.id.disco_detalle, disco, limDisco, Formato.entero(discoTotal * disco / 100) + " de "
                + Formato.entero(discoTotal) + " GB usados");

        ((TextView) findViewById(R.id.red_valor)).setText("↑ " + Formato.entero(muestra.optDouble("red_envio_kbps", 0))
                + "  ↓ " + Formato.entero(muestra.optDouble("red_recepcion_kbps", 0)));
        ((TextView) findViewById(R.id.red_detalle)).setText("kbps de envío y recepción");
        etiqueta((TextView) findViewById(R.id.red_etiqueta), "Normal", R.color.fondo_exito, R.color.exito);

        listaMayorConsumo.removeAllViews();
        JSONArray procesos = estado.optJSONArray("procesos");
        if (procesos != null) {
            LayoutInflater inflador = LayoutInflater.from(this);
            for (int i = 0; i < procesos.length(); i++) {
                View fila = inflador.inflate(R.layout.item_proceso, listaMayorConsumo, false);
                ProcesoAdapter.llenar(fila, procesos.optJSONObject(i));
                listaMayorConsumo.addView(fila);
            }
        }

        if (alertasActivas.length() > 0) {
            JSONObject a = alertasActivas.optJSONObject(0);
            textoBanner.setText("Alerta " + a.optString("severidad") + ": " + a.optString("mensaje")
                    + "\nToque para ver las alertas.");
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setVisibility(View.GONE);
        }
    }

    /** Valor, barra y etiqueta de texto (Normal, Vigilar, Alerta) de un recurso. */
    private void indicador(int idValor, int idBarra, int idEtiqueta, int idDetalle,
                           double valor, double limite, String detalle) {
        ((TextView) findViewById(idValor)).setText(Formato.porcentaje(valor));
        ((TextView) findViewById(idDetalle)).setText(detalle);
        ProgressBar barraRecurso = findViewById(idBarra);
        barraRecurso.setProgress((int) Math.round(valor));
        TextView etiqueta = findViewById(idEtiqueta);

        int color;
        if (valor > limite) {
            color = R.color.peligro;
            etiqueta(etiqueta, "Alerta", R.color.fondo_peligro, R.color.texto_peligro);
        } else if (valor > limite - 15) {
            color = R.color.acento;
            etiqueta(etiqueta, "Vigilar", R.color.fondo_aviso, R.color.texto_aviso);
        } else {
            color = R.color.exito;
            etiqueta(etiqueta, "Normal", R.color.fondo_exito, R.color.exito);
        }
        barraRecurso.setProgressTintList(ColorStateList.valueOf(ContextCompat.getColor(this, color)));
    }

    private void etiqueta(TextView v, String texto, int fondo, int letra) {
        v.setText(texto);
        v.setTextColor(ContextCompat.getColor(this, letra));
        v.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, fondo)));
    }

    private static double limite(JSONArray umbrales, String recurso, double porDefecto) {
        if (umbrales == null) {
            return porDefecto;
        }
        for (int i = 0; i < umbrales.length(); i++) {
            JSONObject u = umbrales.optJSONObject(i);
            if (u != null && recurso.equals(u.optString("recurso"))) {
                return u.optDouble("valor_limite", porDefecto);
            }
        }
        return porDefecto;
    }

    private void cambiarEstadoAlerta(int idAlerta, String nuevoEstado) {
        final ApiCliente cliente = api;
        red.execute(() -> {
            try {
                String cuerpo = new JSONObject().put("estado", nuevoEstado).toString();
                cliente.post("/api/alertas/" + idAlerta, cuerpo);
                principal.post(() -> {
                    Toast.makeText(this, getString(R.string.alerta_actualizada,
                            nuevoEstado.toLowerCase(Formato.ES)), Toast.LENGTH_SHORT).show();
                    actualizar();
                });
            } catch (Exception e) {
                principal.post(() -> Toast.makeText(this, R.string.error_accion, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void dialogoServidor() {
        EditText campo = new EditText(this);
        campo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        campo.setText(api.getBase());
        FrameLayout contenedor = new FrameLayout(this);
        int margen = Math.round(20 * getResources().getDisplayMetrics().density);
        contenedor.setPadding(margen, 0, margen, 0);
        contenedor.addView(campo);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.servidor_titulo)
                .setMessage(R.string.servidor_ayuda)
                .setView(contenedor)
                .setPositiveButton(R.string.guardar, (dialogo, boton) -> {
                    String url = campo.getText().toString().trim();
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "http://" + url;
                    }
                    getSharedPreferences(PREFERENCIAS, MODE_PRIVATE).edit()
                            .putString(CLAVE_SERVIDOR, url).apply();
                    api = new ApiCliente(url);
                    estadoConexion.setText(R.string.conectando);
                    actualizar();
                })
                .setNegativeButton(R.string.cancelar, null)
                .show();
    }
}
