package com.umb.mirs;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Gráfica en vivo de CPU y RAM (0 a 100 %), dibujada con Canvas:
 * curvas suavizadas, área en degradado bajo la CPU, línea discontinua del umbral,
 * punto que pulsa en el último dato y lectura del valor al tocar la gráfica.
 */
public class GraficaLineas extends View {

    private float[] cpu = new float[0];
    private float[] ram = new float[0];
    private float umbralCpu = -1f;
    private float intervaloSeg = 2f;
    private int colorCpu;
    private final int colorRam;

    private final Paint lineaCpu = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineaRam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rejilla = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texto = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint umbral = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint punto = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint globo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trazo = new Path();
    private final RectF caja = new RectF();

    private float revelado = 1f;          // animación de entrada (0 a 1)
    private boolean primeraVez = true;
    private int tocado = -1;              // índice que el usuario está consultando

    public GraficaLineas(Context context) {
        this(context, null);
    }

    public GraficaLineas(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        colorCpu = ContextCompat.getColor(context, R.color.azul_claro);
        colorRam = ContextCompat.getColor(context, R.color.acento);
        lineaCpu.setStyle(Paint.Style.STROKE);
        lineaCpu.setStrokeWidth(2.4f * d);
        lineaCpu.setStrokeJoin(Paint.Join.ROUND);
        lineaCpu.setStrokeCap(Paint.Cap.ROUND);
        lineaRam.setStyle(Paint.Style.STROKE);
        lineaRam.setStrokeWidth(1.8f * d);
        lineaRam.setColor(colorRam);
        lineaRam.setAlpha(210);
        rejilla.setColor(0x12FFFFFF);
        rejilla.setStrokeWidth(d);
        texto.setColor(ContextCompat.getColor(context, R.color.texto_tenue));
        texto.setTextSize(9.5f * d);
        umbral.setStyle(Paint.Style.STROKE);
        umbral.setStrokeWidth(1.2f * d);
        umbral.setColor(ContextCompat.getColor(context, R.color.peligro));
        umbral.setAlpha(170);
        umbral.setPathEffect(new DashPathEffect(new float[]{5f * d, 4f * d}, 0f));
        globo.setColor(0xF0111827);
    }

    public void setDatos(float[] cpu, float[] ram, float umbralCpu, int colorCpu, float intervaloSeg) {
        this.cpu = cpu;
        this.ram = ram;
        this.umbralCpu = umbralCpu;
        this.colorCpu = colorCpu;
        this.intervaloSeg = intervaloSeg;
        if (primeraVez && cpu.length > 1) {
            primeraVez = false;
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(900);
            a.setInterpolator(new DecelerateInterpolator());
            a.addUpdateListener(x -> {
                revelado = (float) x.getAnimatedValue();
                invalidate();
            });
            a.start();
        }
        invalidate();
    }

    private float izq, der, arriba, abajo;

    private float x(int i, int n) {
        return izq + (der - izq) * i / (float) (n - 1);
    }

    private float y(float v) {
        return abajo - (abajo - arriba) * Math.max(0f, Math.min(100f, v)) / 100f;
    }

    /** Curva suave que pasa por todos los puntos (Bézier con controles en el punto medio). */
    private void curva(float[] serie, int hasta) {
        trazo.reset();
        int n = serie.length;
        trazo.moveTo(x(0, n), y(serie[0]));
        for (int i = 1; i <= hasta; i++) {
            float x0 = x(i - 1, n), x1 = x(i, n), xm = (x0 + x1) / 2;
            trazo.cubicTo(xm, y(serie[i - 1]), xm, y(serie[i]), x1, y(serie[i]));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        izq = 22 * d;
        der = getWidth() - 10 * d;
        arriba = 8 * d;
        abajo = getHeight() - 6 * d;

        for (int v = 0; v <= 100; v += 50) {
            canvas.drawLine(izq, y(v), der, y(v), rejilla);
            canvas.drawText(String.valueOf(v), 0, y(v) + 3 * d, texto);
        }
        if (umbralCpu > 0) {
            trazo.reset();
            trazo.moveTo(izq, y(umbralCpu));
            trazo.lineTo(der, y(umbralCpu));
            canvas.drawPath(trazo, umbral);
        }
        int n = cpu.length;
        if (n < 2) {
            canvas.drawText("Esperando muestras…", izq + 8 * d, (arriba + abajo) / 2, texto);
            return;
        }
        int hasta = Math.max(1, Math.round((n - 1) * revelado));

        curva(ram, Math.min(hasta, ram.length - 1));
        canvas.drawPath(trazo, lineaRam);

        curva(cpu, hasta);
        Path linea = new Path(trazo);
        trazo.lineTo(x(hasta, n), abajo);
        trazo.lineTo(x(0, n), abajo);
        trazo.close();
        area.setShader(new LinearGradient(0, arriba, 0, abajo,
                Color.argb(115, Color.red(colorCpu), Color.green(colorCpu), Color.blue(colorCpu)),
                Color.argb(0, Color.red(colorCpu), Color.green(colorCpu), Color.blue(colorCpu)),
                Shader.TileMode.CLAMP));
        canvas.drawPath(trazo, area);
        lineaCpu.setColor(colorCpu);
        lineaCpu.setShadowLayer(4 * d, 0, 0, colorCpu);
        canvas.drawPath(linea, lineaCpu);

        // punto que pulsa en el último dato
        float px = x(hasta, n), py = y(cpu[hasta]);
        float fase = (SystemClock.uptimeMillis() % 1400) / 1400f;
        punto.setColor(colorCpu);
        punto.setAlpha((int) (120 * (1 - fase)));
        canvas.drawCircle(px, py, (4 + 10 * fase) * d, punto);
        punto.setColor(Color.WHITE);
        punto.setAlpha(255);
        canvas.drawCircle(px, py, 3.5f * d, punto);

        if (tocado >= 0 && tocado < n) {
            dibujarLectura(canvas, d, n);
        }
        if (ValueAnimator.areAnimatorsEnabled() && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    private void dibujarLectura(Canvas canvas, float d, int n) {
        float tx = x(tocado, n);
        canvas.drawLine(tx, arriba, tx, abajo, rejilla);
        punto.setColor(colorCpu);
        canvas.drawCircle(tx, y(cpu[tocado]), 4 * d, punto);
        punto.setColor(colorRam);
        if (tocado < ram.length) {
            canvas.drawCircle(tx, y(ram[tocado]), 3.5f * d, punto);
        }
        int segundos = Math.round((n - 1 - tocado) * intervaloSeg);
        String l1 = "CPU " + Formato.porcentaje(cpu[tocado]) + "  ·  RAM "
                + (tocado < ram.length ? Formato.porcentaje(ram[tocado]) : "-");
        String l2 = segundos == 0 ? "ahora" : "hace " + (segundos >= 60 ? (segundos / 60) + " min " + (segundos % 60) + " s" : segundos + " s");
        Paint t = new Paint(texto);
        t.setColor(Color.WHITE);
        t.setTextSize(11 * d);
        float ancho = Math.max(t.measureText(l1), t.measureText(l2)) + 16 * d;
        float bx = Math.max(izq, Math.min(der - ancho, tx - ancho / 2));
        caja.set(bx, arriba, bx + ancho, arriba + 38 * d);
        canvas.drawRoundRect(caja, 8 * d, 8 * d, globo);
        canvas.drawText(l1, bx + 8 * d, arriba + 16 * d, t);
        t.setColor(ContextCompat.getColor(getContext(), R.color.texto_suave));
        canvas.drawText(l2, bx + 8 * d, arriba + 31 * d, t);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int n = cpu.length;
        if (n < 2) {
            return super.onTouchEvent(e);
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                getParent().requestDisallowInterceptTouchEvent(true);
                float rel = (e.getX() - izq) / Math.max(1f, der - izq);
                tocado = Math.max(0, Math.min(n - 1, Math.round(rel * (n - 1))));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                // fallthrough
            case MotionEvent.ACTION_CANCEL:
                tocado = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
