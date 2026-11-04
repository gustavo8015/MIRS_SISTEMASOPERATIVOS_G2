package com.umb.mirs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/** Gráfica de líneas de CPU y RAM (0 a 100 %) dibujada con Canvas, sin librerías externas. */
public class GraficaLineas extends View {

    private float[] cpu = new float[0];
    private float[] ram = new float[0];
    private float umbralCpu = -1f;

    private final Paint lineaCpu = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineaRam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rejilla = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texto = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint umbral = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trazo = new Path();

    public GraficaLineas(Context context) {
        this(context, null);
    }

    public GraficaLineas(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        configurar(lineaCpu, ContextCompat.getColor(context, R.color.primario), 2.2f * d);
        configurar(lineaRam, ContextCompat.getColor(context, R.color.acento), 2f * d);
        rejilla.setColor(ContextCompat.getColor(context, R.color.pista));
        rejilla.setStrokeWidth(1f * d);
        texto.setColor(ContextCompat.getColor(context, R.color.texto_suave));
        texto.setTextSize(10f * d);
        umbral.setColor(ContextCompat.getColor(context, R.color.peligro));
        umbral.setStrokeWidth(1.2f * d);
        umbral.setStyle(Paint.Style.STROKE);
        umbral.setPathEffect(new DashPathEffect(new float[]{6f * d, 4f * d}, 0f));
    }

    private static void configurar(Paint p, int color, float ancho) {
        p.setColor(color);
        p.setStrokeWidth(ancho);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setDatos(float[] cpu, float[] ram, float umbralCpu) {
        this.cpu = cpu;
        this.ram = ram;
        this.umbralCpu = umbralCpu;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float izq = 28 * d, der = getWidth() - 6 * d, arriba = 6 * d, abajo = getHeight() - 6 * d;
        float alto = abajo - arriba;

        for (int v = 0; v <= 100; v += 25) {
            float y = abajo - alto * v / 100f;
            canvas.drawLine(izq, y, der, y, rejilla);
            canvas.drawText(String.valueOf(v), 2 * d, y + 4 * d, texto);
        }
        if (umbralCpu > 0) {
            float y = abajo - alto * umbralCpu / 100f;
            trazo.reset();
            trazo.moveTo(izq, y);
            trazo.lineTo(der, y);
            canvas.drawPath(trazo, umbral);
            canvas.drawText(Formato.porcentaje(umbralCpu) + " umbral CPU", izq + 4 * d, y + 12 * d, texto);
        }
        if (cpu.length < 2) {
            canvas.drawText("Esperando muestras…", izq + 8 * d, arriba + alto / 2, texto);
            return;
        }
        dibujarSerie(canvas, ram, lineaRam, izq, der, abajo, alto);
        dibujarSerie(canvas, cpu, lineaCpu, izq, der, abajo, alto);
    }

    private void dibujarSerie(Canvas canvas, float[] serie, Paint pincel,
                              float izq, float der, float abajo, float alto) {
        trazo.reset();
        float paso = (der - izq) / (serie.length - 1);
        for (int i = 0; i < serie.length; i++) {
            float valor = Math.max(0f, Math.min(100f, serie[i]));
            float x = izq + i * paso;
            float y = abajo - alto * valor / 100f;
            if (i == 0) {
                trazo.moveTo(x, y);
            } else {
                trazo.lineTo(x, y);
            }
        }
        canvas.drawPath(trazo, pincel);
    }
}
