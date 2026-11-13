package com.umb.mirs;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Mapa de calor de los núcleos lógicos: un cuadro por núcleo, coloreado según su carga.
 * Permite ver cómo el planificador del sistema operativo reparte el trabajo entre los núcleos.
 */
public class MapaNucleosView extends View {

    private static final int COLUMNAS = 8;
    private final Paint celda = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numero = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF caja = new RectF();
    private float[] mostrados = new float[0];
    private float[] desde = new float[0];
    private float[] hacia = new float[0];
    private ValueAnimator animador;
    private final int verde, ambar, rojo;

    public MapaNucleosView(Context context) {
        this(context, null);
    }

    public MapaNucleosView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        verde = ContextCompat.getColor(context, R.color.exito);
        ambar = ContextCompat.getColor(context, R.color.acento);
        rojo = ContextCompat.getColor(context, R.color.peligro);
        numero.setColor(0xCCFFFFFF);
        numero.setTextAlign(Paint.Align.CENTER);
        numero.setTextSize(9 * getResources().getDisplayMetrics().density);
    }

    public void setUsos(float[] usos) {
        boolean cambiaCantidad = usos.length != mostrados.length;
        desde = cambiaCantidad ? new float[usos.length] : mostrados.clone();
        hacia = usos.clone();
        if (cambiaCantidad) {
            mostrados = new float[usos.length];
            requestLayout();
        }
        if (animador != null) {
            animador.cancel();
        }
        animador = ValueAnimator.ofFloat(0f, 1f);
        animador.setDuration(600);
        animador.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 0; i < mostrados.length; i++) {
                mostrados[i] = desde[i] + (hacia[i] - desde[i]) * t;
            }
            invalidate();
        });
        animador.start();
    }

    @Override
    protected void onMeasure(int anchoSpec, int altoSpec) {
        float d = getResources().getDisplayMetrics().density;
        int filas = Math.max(1, (int) Math.ceil(mostrados.length / (double) COLUMNAS));
        int alto = Math.round(filas * 22 * d + (filas - 1) * 6 * d);
        setMeasuredDimension(MeasureSpec.getSize(anchoSpec), alto);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animador != null) {
            animador.cancel();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float hueco = 6 * d, alto = 22 * d;
        float ancho = (getWidth() - hueco * (COLUMNAS - 1)) / COLUMNAS;
        for (int i = 0; i < mostrados.length; i++) {
            int fila = i / COLUMNAS, col = i % COLUMNAS;
            float x = col * (ancho + hueco), y = fila * (alto + hueco);
            caja.set(x, y, x + ancho, y + alto);
            float v = mostrados[i];
            int color = v > 85 ? rojo : v > 60 ? ambar : verde;
            celda.setColor(color);
            celda.setAlpha((int) (255 * Math.min(1f, 0.3f + v / 140f)));
            canvas.drawRoundRect(caja, 5 * d, 5 * d, celda);
            canvas.drawText(String.valueOf(i), caja.centerX(), caja.centerY() - (numero.descent() + numero.ascent()) / 2, numero);
        }
    }
}
