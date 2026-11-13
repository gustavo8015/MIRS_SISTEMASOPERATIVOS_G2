package com.umb.mirs;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Anillo de progreso animado: el arco se llena con una animación suave, el color cambia
 * con el estado (verde, ámbar, rojo) y la cifra del centro sube como un contador.
 * Mientras no hay datos muestra un arco girando (estado de carga).
 */
public class AnilloView extends View {

    private final Paint pista = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arco = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texto = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF caja = new RectF();
    private final ArgbEvaluator mezcla = new ArgbEvaluator();

    private float mostrado = 0f;
    private int colorMostrado;
    private String centro;
    private boolean cargando = true;
    private boolean mostrarTexto = true;
    private float grosor;
    private float giro = 0f;
    private ValueAnimator animador;

    public AnilloView(Context context) {
        this(context, null);
    }

    public AnilloView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        grosor = 8 * d;
        colorMostrado = ContextCompat.getColor(context, R.color.azul_claro);
        pista.setStyle(Paint.Style.STROKE);
        pista.setColor(0x14FFFFFF);
        arco.setStyle(Paint.Style.STROKE);
        arco.setStrokeCap(Paint.Cap.ROUND);
        texto.setColor(ContextCompat.getColor(context, R.color.texto));
        texto.setTextAlign(Paint.Align.CENTER);
        texto.setFakeBoldText(true);
    }

    public void setGrosor(float dp) {
        grosor = dp * getResources().getDisplayMetrics().density;
        invalidate();
    }

    public void setMostrarTexto(boolean mostrar) {
        mostrarTexto = mostrar;
        invalidate();
    }

    /** Texto fijo para el centro (por ejemplo "539" en la red); null muestra el porcentaje. */
    public void setTextoCentro(@Nullable String texto) {
        centro = texto;
        invalidate();
    }

    public void setCargando() {
        cargando = true;
        invalidate();
    }

    public void setValor(float valor, int color) {
        float destino = Math.max(0f, Math.min(100f, valor));
        float desde = cargando ? 0f : mostrado;
        int colorDesde = colorMostrado;
        cargando = false;
        if (animador != null) {
            animador.cancel();
        }
        animador = ValueAnimator.ofFloat(0f, 1f);
        animador.setDuration(700);
        animador.setInterpolator(new DecelerateInterpolator(1.6f));
        animador.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            mostrado = desde + (destino - desde) * t;
            colorMostrado = (int) mezcla.evaluate(t, colorDesde, color);
            invalidate();
        });
        animador.start();
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
        float lado = Math.min(getWidth(), getHeight());
        float margen = grosor / 2 + 6 * d; // espacio para el brillo
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        caja.set(cx - lado / 2 + margen, cy - lado / 2 + margen, cx + lado / 2 - margen, cy + lado / 2 - margen);

        pista.setStrokeWidth(grosor);
        arco.setStrokeWidth(grosor);
        canvas.drawArc(caja, 0, 360, false, pista);

        if (cargando) {
            arco.setColor(0x6693C5FD);
            arco.clearShadowLayer();
            canvas.drawArc(caja, giro, 80, false, arco);
            giro = (giro + 6) % 360;
            postInvalidateOnAnimation();
            return;
        }
        arco.setColor(colorMostrado);
        arco.setShadowLayer(6 * d, 0, 0, colorMostrado);
        if (mostrado > 0.5f) {
            canvas.drawArc(caja, -90, 360 * mostrado / 100f, false, arco);
        }
        if (mostrarTexto) {
            String valor = centro != null ? centro : Math.round(mostrado) + "%";
            texto.setTextSize(lado * 0.2f);
            canvas.drawText(valor, cx, cy - (texto.descent() + texto.ascent()) / 2, texto);
        }
    }
}
