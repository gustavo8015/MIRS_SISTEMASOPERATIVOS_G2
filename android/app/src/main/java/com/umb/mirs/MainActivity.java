package com.umb.mirs;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
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
    private static final String PREFIJO_BLUETOOTH = "bt:";

    private ConexionMirs api;
    private boolean consultando = false;
    private int pestana = R.id.nav_panel;
    private boolean soloActivas = true;
    private int fallosSeguidos = 0;
    private int ultimaAlertaVista = -1;
    private float cpuEnPantalla = 0f;
    private double maximoRed = 100;

    private TextView subtitulo, estadoConexion, textoSinConexion;
    private View pastilla, puntoVivo, vistaSinConexion;
    private View vistaPanel, vistaProcesos, vistaAlertas;
    private BottomNavigationView navegacion;
    private SwipeRefreshLayout refrescar;

    private AnilloView anilloCpu, anilloRam, anilloDisco, anilloRed;
    private Chip3DView chip;
    private TextView cpuValor, cpuEstado, ramDetalle, discoDetalle, redDetalle;
    private View banner;
    private PulsoView pulso;
    private TextView textoBanner, leyenda, nucleosTotal, nucleosAviso, sinAlertas;
    private GraficaLineas grafica;
    private MapaNucleosView mapaNucleos;
    private LinearLayout listaMayorConsumo;

    private final ProcesoAdapter procesosAdapter = new ProcesoAdapter();

    private SelectorBluetooth selector;

    /**
     * Permisos para buscar y conectar por Bluetooth: en Android 12 o superior, "dispositivos
     * cercanos" (BLUETOOTH_SCAN y BLUETOOTH_CONNECT); en versiones anteriores, la ubicación,
     * que Android exige para buscar equipos Bluetooth.
     */
    private final ActivityResultLauncher<String[]> permisosBluetooth =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), resultado -> {
                if (tienePermisosBusqueda()) {
                    elegirEquipoBluetooth();
                } else {
                    Toast.makeText(this, R.string.bt_permiso, Toast.LENGTH_LONG).show();
                }
            });
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
        api = conexionGuardada(prefs.getString(CLAVE_SERVIDOR, SERVIDOR_POR_DEFECTO));

        // encabezado: "MIRS" en blanco y "Móvil" en azul
        TextView titulo = findViewById(R.id.titulo);
        SpannableString nombre = new SpannableString(getString(R.string.app_name));
        nombre.setSpan(new ForegroundColorSpan(color(R.color.azul_claro)), 5, nombre.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        titulo.setText(nombre);
        subtitulo = findViewById(R.id.subtitulo);
        pastilla = findViewById(R.id.pastilla_estado);
        puntoVivo = findViewById(R.id.punto_vivo);
        estadoConexion = findViewById(R.id.estado_conexion);
        findViewById(R.id.btn_buscar).setOnClickListener(v -> buscarEquipos());
        findViewById(R.id.btn_bluetooth).setOnClickListener(v -> conectarPorBluetooth());
        findViewById(R.id.btn_servidor).setOnClickListener(v -> dialogoServidor());

        // el punto de "EN VIVO" late
        ObjectAnimator latido = ObjectAnimator.ofFloat(puntoVivo, "alpha", 1f, 0.25f);
        latido.setDuration(800);
        latido.setRepeatCount(ValueAnimator.INFINITE);
        latido.setRepeatMode(ValueAnimator.REVERSE);
        latido.start();

        vistaSinConexion = findViewById(R.id.vista_sin_conexion);
        textoSinConexion = findViewById(R.id.texto_sin_conexion);
        findViewById(R.id.btn_reintentar).setOnClickListener(v -> actualizar());
        findViewById(R.id.btn_buscar_equipos).setOnClickListener(v -> buscarEquipos());
        findViewById(R.id.btn_conectar_bt).setOnClickListener(v -> conectarPorBluetooth());

        vistaPanel = findViewById(R.id.vista_panel);
        vistaProcesos = findViewById(R.id.vista_procesos);
        vistaAlertas = findViewById(R.id.vista_alertas);
        // El <include> de activity_main le da a la raíz de vista_panel el id vista_panel,
        // así que el SwipeRefreshLayout se busca con ese id (refrescar_panel deja de existir).
        refrescar = findViewById(R.id.vista_panel);
        refrescar.setOnRefreshListener(this::actualizar);
        refrescar.setProgressBackgroundColorSchemeColor(color(R.color.tarjeta));
        refrescar.setColorSchemeColors(color(R.color.azul_claro));

        anilloCpu = findViewById(R.id.anillo_cpu);
        anilloCpu.setGrosor(10);
        anilloCpu.setMostrarTexto(false);
        anilloRam = findViewById(R.id.anillo_ram);
        anilloDisco = findViewById(R.id.anillo_disco);
        anilloRed = findViewById(R.id.anillo_red);
        chip = findViewById(R.id.chip);
        cpuValor = findViewById(R.id.cpu_valor);
        cpuEstado = findViewById(R.id.cpu_estado);
        ramDetalle = findViewById(R.id.ram_detalle);
        discoDetalle = findViewById(R.id.disco_detalle);
        redDetalle = findViewById(R.id.red_detalle);
        banner = findViewById(R.id.banner_alerta);
        pulso = findViewById(R.id.pulso);
        textoBanner = findViewById(R.id.texto_banner);
        grafica = findViewById(R.id.grafica);
        leyenda = findViewById(R.id.leyenda);
        mapaNucleos = findViewById(R.id.mapa_nucleos);
        nucleosTotal = findViewById(R.id.nucleos_total);
        nucleosAviso = findViewById(R.id.nucleos_aviso);
        listaMayorConsumo = findViewById(R.id.lista_mayor_consumo);
        sinAlertas = findViewById(R.id.sin_alertas);

        navegacion = findViewById(R.id.navegacion);
        navegacion.setOnItemSelectedListener(item -> {
            mostrarPestana(item.getItemId());
            return true;
        });
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
        api.cerrar();
        if (selector != null) {
            selector.terminar();
        }
    }

    private int color(int id) {
        return ContextCompat.getColor(this, id);
    }

    /** Cambio de pestaña con una transición corta: la nueva vista entra deslizándose. */
    private void mostrarPestana(int id) {
        int anterior = indice(pestana), nuevo = indice(id);
        pestana = id;
        View[] vistas = {vistaPanel, vistaProcesos, vistaAlertas};
        for (int i = 0; i < vistas.length; i++) {
            vistas[i].setVisibility(i == nuevo ? View.VISIBLE : View.GONE);
        }
        View entra = vistas[nuevo];
        float d = getResources().getDisplayMetrics().density;
        entra.setAlpha(0f);
        entra.setTranslationX((nuevo >= anterior ? 1 : -1) * 36 * d);
        entra.animate().alpha(1f).translationX(0f).setDuration(240)
                .setInterpolator(new DecelerateInterpolator()).start();
        actualizar();
    }

    private static int indice(int idPestana) {
        if (idPestana == R.id.nav_procesos) {
            return 1;
        }
        return idPestana == R.id.nav_alertas ? 2 : 0;
    }

    /** Pide a la API lo que necesita la pestaña visible. Nunca hay dos consultas a la vez. */
    private void actualizar() {
        if (consultando) {
            return;
        }
        consultando = true;
        final int tab = pestana;
        final boolean activas = soloActivas;
        final ConexionMirs cliente = api;
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
                    conectado(true, cliente.getBase());
                    pintar(estado, alertasActivas, muestras, umbrales, procesos, alertas);
                });
            } catch (Exception e) {
                principal.post(() -> {
                    consultando = false;
                    refrescar.setRefreshing(false);
                    conectado(false, cliente.getBase());
                });
            }
        });
    }

    /** Pastilla "EN VIVO" / "SIN CONEXIÓN" y pantalla de sin conexión tras dos fallos seguidos. */
    private void conectado(boolean si, String base) {
        fallosSeguidos = si ? 0 : fallosSeguidos + 1;
        int tono = si ? R.color.exito : R.color.peligro;
        estadoConexion.setText(si ? R.string.pastilla_vivo : R.string.pastilla_sin_conexion);
        estadoConexion.setTextColor(si ? 0xFF86EFAC : color(R.color.texto_peligro));
        puntoVivo.setBackgroundTintList(ColorStateList.valueOf(color(tono)));
        pastilla.setBackgroundTintList(si ? null : ColorStateList.valueOf(0x33EF4444));

        if (si && vistaSinConexion.getVisibility() == View.VISIBLE) {
            vistaSinConexion.animate().alpha(0f).setDuration(250)
                    .withEndAction(() -> vistaSinConexion.setVisibility(View.GONE)).start();
        } else if (!si && fallosSeguidos >= 2 && vistaSinConexion.getVisibility() != View.VISIBLE) {
            textoSinConexion.setText(getString(R.string.sin_conexion_detalle, base));
            vistaSinConexion.setAlpha(0f);
            vistaSinConexion.setVisibility(View.VISIBLE);
            vistaSinConexion.animate().alpha(1f).setDuration(250).start();
        }
    }

    private void pintar(JSONObject estado, JSONArray alertasActivas, JSONArray muestras,
                        JSONArray umbrales, JSONArray procesos, JSONArray alertas) {
        JSONObject equipo = estado.optJSONObject("equipo");
        JSONObject muestra = estado.optJSONObject("muestra");
        if (equipo != null) {
            subtitulo.setText(equipo.optString("nombre_host") + " · " + equipo.optString("sistema_operativo")
                    + " · " + equipo.optInt("nucleos_cpu") + " núcleos"
                    + (api instanceof ClienteBluetooth ? " · Bluetooth" : ""));
        }

        int activas = alertasActivas.length();
        BadgeDrawable insignia = navegacion.getOrCreateBadge(R.id.nav_alertas);
        insignia.setNumber(activas);
        insignia.setVisible(activas > 0);
        avisarAlertaNueva(alertasActivas);

        double limCpu = limite(umbrales, "CPU", 85);
        if (muestra != null && equipo != null && umbrales != null) {
            pintarPanel(estado, equipo, muestra, umbrales, alertasActivas);
        }
        if (muestras != null && muestra != null) {
            float[] cpu = new float[muestras.length()];
            float[] ram = new float[muestras.length()];
            for (int i = 0; i < muestras.length(); i++) {
                JSONObject m = muestras.optJSONObject(i);
                cpu[i] = (float) m.optDouble("cpu_pct", 0);
                ram[i] = (float) m.optDouble("ram_pct", 0);
            }
            float intervalo = (float) estado.optDouble("intervalo_seg", 2);
            int colorCpu = colorEstado(muestra.optDouble("cpu_pct", 0), limCpu, R.color.azul_claro);
            grafica.setDatos(cpu, ram, (float) limCpu, colorCpu, intervalo);
            leyenda.setText(leyenda(colorCpu, Math.ceil(muestras.length() * intervalo / 60)));
        }
        if (procesos != null) {
            procesosAdapter.setDatos(procesos);
        }
        if (alertas != null) {
            alertasAdapter.setDatos(alertas);
            sinAlertas.setVisibility(alertas.length() == 0 ? View.VISIBLE : View.GONE);
        }
    }

    private CharSequence leyenda(int colorCpu, double minutos) {
        SpannableStringBuilder t = new SpannableStringBuilder();
        t.append("● ", new ForegroundColorSpan(colorCpu), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.append("CPU   ");
        t.append("● ", new ForegroundColorSpan(color(R.color.acento)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.append("RAM   ").append(Formato.entero(minutos)).append(" min");
        return t;
    }

    private void pintarPanel(JSONObject estado, JSONObject equipo, JSONObject muestra,
                             JSONArray umbrales, JSONArray alertasActivas) {
        double cpu = muestra.optDouble("cpu_pct", 0);
        double ram = muestra.optDouble("ram_pct", 0);
        double disco = muestra.optDouble("disco_pct", 0);
        double limCpu = limite(umbrales, "CPU", 85);
        double limRam = limite(umbrales, "RAM", 90);
        double limDisco = limite(umbrales, "DISCO", 90);

        // CPU: anillo grande, chip 3D y cifra que sube como contador
        int colorCpu = colorEstado(cpu, limCpu, R.color.azul_claro);
        anilloCpu.setValor((float) cpu, colorCpu);
        chip.setEstado((float) cpu, colorCpu);
        animarCifra(cpuValor, cpuEnPantalla, (float) cpu);
        cpuEnPantalla = (float) cpu;
        cpuEstado.setText("CPU · " + nombreEstado(cpu, limCpu));
        cpuEstado.setTextColor(colorCpu);

        double ramTotalGb = equipo.optDouble("ram_total_mb", 0) / 1024.0;
        anilloRam.setValor((float) ram, colorEstado(ram, limRam, R.color.exito));
        ramDetalle.setText(Formato.decimal(ramTotalGb * ram / 100) + " / " + Formato.decimal(ramTotalGb) + " GB");

        double discoTotal = equipo.optDouble("disco_total_gb", 0);
        anilloDisco.setValor((float) disco, colorEstado(disco, limDisco, R.color.exito));
        discoDetalle.setText(Formato.entero(discoTotal * disco / 100) + " / " + Formato.entero(discoTotal) + " GB");

        // Red: el anillo se llena respecto al máximo reciente, que decae poco a poco
        double envio = muestra.optDouble("red_envio_kbps", 0), recepcion = muestra.optDouble("red_recepcion_kbps", 0);
        double total = envio + recepcion;
        maximoRed = Math.max(Math.max(100, maximoRed * 0.97), total);
        anilloRed.setValor((float) (100 * total / maximoRed), color(R.color.primario));
        anilloRed.setTextoCentro(total >= 1000 ? Formato.decimal(total / 1000) + "M" : Formato.entero(total));
        redDetalle.setText("↓ " + Formato.entero(recepcion) + " · ↑ " + Formato.entero(envio) + " kbps");

        // Núcleos lógicos
        JSONArray nucleos = estado.optJSONArray("nucleos");
        nucleosAviso.setVisibility(estado.has("nucleos") ? View.GONE : View.VISIBLE);
        if (nucleos != null && nucleos.length() > 0) {
            float[] usos = new float[nucleos.length()];
            float suma = 0;
            for (int i = 0; i < usos.length; i++) {
                usos[i] = (float) nucleos.optDouble(i, 0);
                suma += usos[i];
            }
            mapaNucleos.setUsos(usos);
            nucleosTotal.setText(usos.length + " · promedio " + Formato.porcentaje(suma / usos.length));
        }

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
            boolean alta = "ALTA".equals(a.optString("severidad"));
            pulso.setColor(color(alta ? R.color.peligro : R.color.acento));
            SpannableStringBuilder t = new SpannableStringBuilder();
            t.append(Formato.tipoAlerta(a.optString("tipo")),
                    new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            t.append("\n").append(a.optString("mensaje"));
            textoBanner.setText(t);
            if (banner.getVisibility() != View.VISIBLE) {
                banner.setAlpha(0f);
                banner.setVisibility(View.VISIBLE);
                banner.animate().alpha(1f).setDuration(300).start();
            }
        } else {
            banner.setVisibility(View.GONE);
        }
    }

    /** Vibra y avisa cuando aparece una alerta con un id mayor que la última vista. */
    private void avisarAlertaNueva(JSONArray alertasActivas) {
        int mayor = 0;
        String tipo = "";
        for (int i = 0; i < alertasActivas.length(); i++) {
            JSONObject a = alertasActivas.optJSONObject(i);
            if (a != null && a.optInt("id_alerta") > mayor) {
                mayor = a.optInt("id_alerta");
                tipo = a.optString("tipo");
            }
        }
        if (ultimaAlertaVista >= 0 && mayor > ultimaAlertaVista) {
            Vibrator vibrador = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrador != null && vibrador.hasVibrator()) {
                vibrador.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE));
            }
            Toast.makeText(this, getString(R.string.nueva_alerta, Formato.tipoAlerta(tipo)), Toast.LENGTH_SHORT).show();
        }
        ultimaAlertaVista = Math.max(ultimaAlertaVista, mayor);
    }

    private void animarCifra(TextView vista, float desde, float hasta) {
        ValueAnimator a = ValueAnimator.ofFloat(desde, hasta);
        a.setDuration(700);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(x -> vista.setText(Formato.porcentaje((float) x.getAnimatedValue())));
        a.start();
    }

    /** Verde (o el color base) en uso normal, ámbar al acercarse al umbral y rojo al superarlo. */
    private int colorEstado(double valor, double limite, int colorNormal) {
        if (valor > limite) {
            return color(R.color.peligro);
        }
        return valor > limite - 15 ? color(R.color.acento) : color(colorNormal);
    }

    private static String nombreEstado(double valor, double limite) {
        if (valor > limite) {
            return "ALERTA";
        }
        return valor > limite - 15 ? "VIGILAR" : "NORMAL";
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
        final ConexionMirs cliente = api;
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

    /** Busca servidores en la red y deja elegir uno; así no hay que escribir la IP. */
    private void buscarEquipos() {
        ProgressBar progreso = new ProgressBar(this);
        LinearLayout contenido = new LinearLayout(this);
        int margen = Math.round(24 * getResources().getDisplayMetrics().density);
        contenido.setPadding(margen, margen / 2, margen, 0);
        contenido.addView(progreso, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        androidx.appcompat.app.AlertDialog espera = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.buscando)
                .setView(contenido)
                .setCancelable(false)
                .show();

        // la búsqueda usa su propio hilo para no esperar detrás de la consulta periódica
        new Thread(() -> {
            List<DescubridorEquipos.Equipo> equipos = DescubridorEquipos.buscar(2000);
            principal.post(() -> {
                espera.dismiss();
                if (isFinishing()) {
                    return;
                }
                if (equipos.isEmpty()) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.ninguno_encontrado)
                            .setMessage(R.string.ayuda_busqueda)
                            .setPositiveButton(R.string.escribir_direccion, (d, b) -> dialogoServidor())
                            .setNegativeButton(R.string.cerrar, null)
                            .show();
                    return;
                }
                String[] etiquetas = new String[equipos.size()];
                for (int i = 0; i < equipos.size(); i++) {
                    etiquetas[i] = equipos.get(i).etiqueta();
                }
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.equipos_encontrados)
                        .setItems(etiquetas, (d, i) -> {
                            DescubridorEquipos.Equipo elegido = equipos.get(i);
                            usarConexion(new ApiCliente(elegido.url), elegido.url);
                            Toast.makeText(this, getString(R.string.conectado_a, elegido.nombre),
                                    Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.cancelar, null)
                        .show();
            });
        }, "mirs-busqueda").start();
    }

    /** Cambia a otra conexión (red o Bluetooth) y la recuerda para la próxima vez. */
    private void usarConexion(ConexionMirs nueva, String clave) {
        getSharedPreferences(PREFERENCIAS, MODE_PRIVATE).edit().putString(CLAVE_SERVIDOR, clave).apply();
        final ConexionMirs anterior = api;
        red.execute(anterior::cerrar);  // se cierra en el hilo de red, después de la consulta en curso
        api = nueva;
        estadoConexion.setText(R.string.pastilla_conectando);
        fallosSeguidos = 0;
        ultimaAlertaVista = -1;
        actualizar();
    }

    private void dialogoServidor() {
        EditText campo = new EditText(this);
        campo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        campo.setText(api instanceof ApiCliente ? api.getBase() : "http://");
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
                    usarConexion(new ApiCliente(url), url);
                })
                .setNegativeButton(R.string.cancelar, null)
                .show();
    }

    /** Recrea la última conexión usada: "bt:MAC" para Bluetooth o una URL para la red. */
    @SuppressLint("MissingPermission")
    private ConexionMirs conexionGuardada(String clave) {
        if (clave.startsWith(PREFIJO_BLUETOOTH)) {
            BluetoothAdapter adaptador = adaptadorBluetooth();
            if (adaptador != null && tienePermisoBluetooth()) {
                try {
                    BluetoothDevice equipo = adaptador.getRemoteDevice(clave.substring(PREFIJO_BLUETOOTH.length()));
                    return new ClienteBluetooth(equipo, nombreEquipo(equipo));
                } catch (IllegalArgumentException direccionInvalida) {
                    // se usa la red por defecto
                }
            }
            return new ApiCliente(SERVIDOR_POR_DEFECTO);
        }
        return new ApiCliente(clave);
    }

    private BluetoothAdapter adaptadorBluetooth() {
        BluetoothManager gestor = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        return gestor == null ? null : gestor.getAdapter();
    }

    private boolean tienePermisoBluetooth() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String[] permisosBusqueda() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean tienePermisosBusqueda() {
        for (String permiso : permisosBusqueda()) {
            if (ContextCompat.checkSelfPermission(this, permiso) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private static String nombreEquipo(BluetoothDevice equipo) {
        return SelectorBluetooth.nombre(equipo);
    }

    private void conectarPorBluetooth() {
        if (adaptadorBluetooth() == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.menu_bluetooth)
                    .setMessage(R.string.bt_sin_adaptador)
                    .setPositiveButton(R.string.cerrar, null)
                    .show();
            return;
        }
        if (tienePermisosBusqueda()) {
            elegirEquipoBluetooth();
        } else {
            permisosBluetooth.launch(permisosBusqueda());
        }
    }

    /** Busca los dispositivos Bluetooth cercanos; al elegir uno lo empareja si hace falta y se conecta. */
    private void elegirEquipoBluetooth() {
        BluetoothAdapter adaptador = adaptadorBluetooth();
        if (adaptador == null) {
            return;
        }
        if (!adaptador.isEnabled()) {
            Toast.makeText(this, R.string.bt_apagado, Toast.LENGTH_LONG).show();
            return;
        }
        if (selector != null) {
            selector.terminar();
        }
        selector = new SelectorBluetooth(this, adaptador, (equipo, nombre) -> {
            Toast.makeText(this, getString(R.string.bt_conectando, nombre), Toast.LENGTH_SHORT).show();
            usarConexion(new ClienteBluetooth(equipo, nombre), PREFIJO_BLUETOOTH + equipo.getAddress());
        });
        selector.mostrar();
    }
}
