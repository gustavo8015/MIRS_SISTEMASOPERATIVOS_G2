package com.umb.mirs;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Busca dispositivos Bluetooth cercanos y deja elegir uno.
 *
 * Muestra primero los equipos ya emparejados y va agregando los que encuentra la búsqueda
 * (startDiscovery). Si el elegido no está emparejado, lo empareja (createBond): Android muestra
 * su ventana de emparejamiento y, cuando el portátil acepta, se avisa a la app para conectarse.
 */
@SuppressLint("MissingPermission") // MainActivity pide los permisos antes de crear esta clase
final class SelectorBluetooth {

    interface AlElegir {
        void elegido(BluetoothDevice equipo, String nombre);
    }

    private final AppCompatActivity actividad;
    private final BluetoothAdapter adaptador;
    private final AlElegir alElegir;
    private final List<BluetoothDevice> equipos = new ArrayList<>();
    private final List<String> etiquetas = new ArrayList<>();
    private int emparejados = 0;
    private ArrayAdapter<String> lista;
    private AlertDialog dialogo;
    private boolean registrado = false;
    private BluetoothDevice pendiente;

    private final BroadcastReceiver receptor = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation") // getParcelableExtra(String, Class) existe solo desde Android 13
        public void onReceive(Context contexto, Intent intento) {
            String accion = intento.getAction();
            BluetoothDevice equipo = intento.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (BluetoothDevice.ACTION_FOUND.equals(accion)) {
                agregar(equipo);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(accion)) {
                if (dialogo != null && dialogo.isShowing()) {
                    dialogo.setTitle(equipos.isEmpty() ? R.string.bt_ninguno_cerca : R.string.bt_dispositivos);
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(accion)) {
                if (pendiente == null || equipo == null || !equipo.getAddress().equals(pendiente.getAddress())) {
                    return;
                }
                int estado = intento.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                if (estado == BluetoothDevice.BOND_BONDED) {
                    BluetoothDevice listo = pendiente;
                    pendiente = null;
                    terminar();
                    alElegir.elegido(listo, nombre(listo));
                } else if (estado == BluetoothDevice.BOND_NONE) {
                    Toast.makeText(actividad, actividad.getString(R.string.bt_emparejado_fallo, nombre(pendiente)),
                            Toast.LENGTH_LONG).show();
                    pendiente = null;
                    terminar();
                }
            }
        }
    };

    SelectorBluetooth(AppCompatActivity actividad, BluetoothAdapter adaptador, AlElegir alElegir) {
        this.actividad = actividad;
        this.adaptador = adaptador;
        this.alElegir = alElegir;
    }

    static String nombre(BluetoothDevice equipo) {
        String n = equipo.getName();
        return n == null || n.isEmpty() ? equipo.getAddress() : n;
    }

    private static boolean esComputador(BluetoothDevice equipo) {
        BluetoothClass clase = equipo.getBluetoothClass();
        return clase != null && clase.getMajorDeviceClass() == BluetoothClass.Device.Major.COMPUTER;
    }

    void mostrar() {
        IntentFilter filtro = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filtro.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filtro.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        actividad.registerReceiver(receptor, filtro);
        registrado = true;

        for (BluetoothDevice equipo : adaptador.getBondedDevices()) {
            agregar(equipo);
        }
        lista = new ArrayAdapter<>(actividad, android.R.layout.simple_list_item_1, etiquetas);
        dialogo = new MaterialAlertDialogBuilder(actividad)
                .setTitle(R.string.bt_buscando)
                .setAdapter(lista, (d, i) -> tocar(i))
                .setNeutralButton(R.string.ayuda, (d, b) -> new MaterialAlertDialogBuilder(actividad)
                        .setTitle(R.string.menu_bluetooth)
                        .setMessage(R.string.bt_ayuda)
                        .setPositiveButton(R.string.cerrar, null)
                        .show())
                .setNegativeButton(R.string.cancelar, null)
                .setOnDismissListener(d -> {
                    if (pendiente == null) {
                        terminar();  // si se está emparejando, el receptor sigue escuchando
                    }
                })
                .show();
        adaptador.cancelDiscovery();
        adaptador.startDiscovery();
    }

    /** Agrega un equipo sin repetirlo: emparejados arriba, luego computadores, luego el resto. */
    private void agregar(BluetoothDevice equipo) {
        if (equipo == null) {
            return;
        }
        for (BluetoothDevice e : equipos) {
            if (e.getAddress().equals(equipo.getAddress())) {
                return;
            }
        }
        boolean emparejado = equipo.getBondState() == BluetoothDevice.BOND_BONDED;
        boolean computador = esComputador(equipo);
        if (!emparejado && !computador && equipo.getName() == null) {
            return;  // dispositivos sin nombre (balizas, relojes, etc.): no se muestran
        }
        String detalle = (emparejado ? "Emparejado · " : "Toque para emparejar · ")
                + (computador ? "Computador · " : "") + equipo.getAddress();
        String etiqueta = nombre(equipo) + "\n" + detalle;
        int posicion;
        if (emparejado) {
            posicion = emparejados++;
        } else if (computador) {
            posicion = emparejados;
        } else {
            posicion = equipos.size();
        }
        equipos.add(posicion, equipo);
        etiquetas.add(posicion, etiqueta);
        if (lista != null) {
            lista.notifyDataSetChanged();
        }
    }

    private void tocar(int indice) {
        BluetoothDevice equipo = equipos.get(indice);
        adaptador.cancelDiscovery();
        if (equipo.getBondState() == BluetoothDevice.BOND_BONDED) {
            terminar();
            alElegir.elegido(equipo, nombre(equipo));
            return;
        }
        pendiente = equipo;
        Toast.makeText(actividad, R.string.bt_emparejando, Toast.LENGTH_LONG).show();
        if (!equipo.createBond()) {
            Toast.makeText(actividad, actividad.getString(R.string.bt_emparejado_fallo, nombre(equipo)),
                    Toast.LENGTH_LONG).show();
            pendiente = null;
            terminar();
        }
    }

    void terminar() {
        if (adaptador.isDiscovering()) {
            adaptador.cancelDiscovery();
        }
        if (registrado) {
            registrado = false;
            try {
                actividad.unregisterReceiver(receptor);
            } catch (IllegalArgumentException yaQuitado) {
                // ya no estaba registrado
            }
        }
    }
}
