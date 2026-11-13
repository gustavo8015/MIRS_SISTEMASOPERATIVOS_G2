package com.umb.mirs;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Modelo 3D de un procesador (cuerpo, núcleo y pines) dibujado con Canvas.
 *
 * El motor es propio y pequeño: rota los vértices en Y y en X, los proyecta con perspectiva,
 * descarta las caras que miran hacia atrás, ordena las visibles de la más lejana a la más
 * cercana (algoritmo del pintor) y las sombrea con una luz direccional.
 *
 * Gira más rápido cuanto mayor es el uso de CPU y el núcleo toma el color del estado.
 * Se puede girar con el dedo.
 */
public class Chip3DView extends View {

    private static final int CUERPO = 0, NUCLEO = 1, PIN = 2;
    private static final float DISTANCIA = 6f;
    private static final float[] LUZ = normalizar(new float[]{0.35f, 0.8f, -0.55f});

    /** Una cara: 4 vértices (x, y, z), su normal hacia afuera y el material. */
    private static final class Cara {
        final float[][] v;
        final float[] n;
        final int material;
        float profundidad;
        final float[] sx = new float[4], sy = new float[4];
        float[] nr = new float[3];

        Cara(float[][] v, float[] n, int material) {
            this.v = v;
            this.n = n;
            this.material = material;
        }
    }

    private final List<Cara> caras = new ArrayList<>();
    private Cara[] orden;
    private final Paint relleno = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borde = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trazo = new Path();

    private float giroY = 35f;
    private float inclinacion = -55f;
    private float cpuMostrada = 0f, cpuObjetivo = 0f;
    private int colorNucleo, colorObjetivo;
    private long ultimoCuadro = 0;
    private float toqueX, toqueY;

    public Chip3DView(Context context) {
        this(context, null);
    }

    public Chip3DView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        colorNucleo = colorObjetivo = ContextCompat.getColor(context, R.color.azul_claro);
        borde.setStyle(Paint.Style.STROKE);
        borde.setStrokeWidth(getResources().getDisplayMetrics().density);
        borde.setColor(0x5593C5FD);
        relleno.setStyle(Paint.Style.FILL);
        construirModelo();
        orden = caras.toArray(new Cara[0]);
        setContentDescription("Modelo 3D del procesador");
    }

    /** Uso de CPU (0 a 100) y color del estado. La transición es suave. */
    public void setEstado(float cpu, int color) {
        cpuObjetivo = Math.max(0f, Math.min(100f, cpu));
        colorObjetivo = color;
        invalidate();
    }

    private void construirModelo() {
        caja(0f, 0f, 0f, 1.0f, 0.14f, 1.0f, CUERPO);
        caja(0f, 0.21f, 0f, 0.5f, 0.07f, 0.5f, NUCLEO);
        for (int i = 0; i < 5; i++) {
            float p = -0.64f + i * 0.32f;
            caja(p, -0.06f, 1.12f, 0.06f, 0.03f, 0.12f, PIN);
            caja(p, -0.06f, -1.12f, 0.06f, 0.03f, 0.12f, PIN);
            caja(1.12f, -0.06f, p, 0.12f, 0.03f, 0.06f, PIN);
            caja(-1.12f, -0.06f, p, 0.12f, 0.03f, 0.06f, PIN);
        }
    }

    private void caja(float cx, float cy, float cz, float hx, float hy, float hz, int material) {
        float x0 = cx - hx, x1 = cx + hx, y0 = cy - hy, y1 = cy + hy, z0 = cz - hz, z1 = cz + hz;
        cara(new float[][]{{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}}, 0, 1, 0, material);
        cara(new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}}, 0, -1, 0, material);
        cara(new float[][]{{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z1}, {x1, y1, z0}}, 1, 0, 0, material);
        cara(new float[][]{{x0, y0, z1}, {x0, y0, z0}, {x0, y1, z0}, {x0, y1, z1}}, -1, 0, 0, material);
        cara(new float[][]{{x0, y0, z1}, {x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}}, 0, 0, 1, material);
        cara(new float[][]{{x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}}, 0, 0, -1, material);
    }

    private void cara(float[][] v, float nx, float ny, float nz, int material) {
        caras.add(new Cara(v, new float[]{nx, ny, nz}, material));
    }

    private static float[] normalizar(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new float[]{v[0] / l, v[1] / l, v[2] / l};
    }

    /** Rota un vector: primero en Y (giro) y luego en X (inclinación). */
    private static void rotar(float[] p, float cosY, float sinY, float cosX, float sinX, float[] salida) {
        float x = p[0] * cosY + p[2] * sinY;
        float z = -p[0] * sinY + p[2] * cosY;
        float y = p[1] * cosX - z * sinX;
        float z2 = p[1] * sinX + z * cosX;
        salida[0] = x;
        salida[1] = y;
        salida[2] = z2;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long ahora = SystemClock.uptimeMillis();
        float dt = ultimoCuadro == 0 ? 0f : Math.min(0.05f, (ahora - ultimoCuadro) / 1000f);
        ultimoCuadro = ahora;

        // transiciones suaves de velocidad y color
        cpuMostrada += (cpuObjetivo - cpuMostrada) * Math.min(1f, dt * 3f);
        colorNucleo = mezclar(colorNucleo, colorObjetivo, Math.min(1f, dt * 3f));
        boolean animar = ValueAnimator.areAnimatorsEnabled();
        if (animar) {
            giroY = (giroY + dt * (12f + cpuMostrada * 1.5f)) % 360f;
        }

        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float lado = Math.min(getWidth(), getHeight());
        float f = lado * 0.5f * DISTANCIA / 1.6f;

        // halo de luz detrás del chip, del color del estado
        halo.setShader(new RadialGradient(cx, cy, lado * 0.42f,
                new int[]{Color.argb(110, Color.red(colorNucleo), Color.green(colorNucleo), Color.blue(colorNucleo)), 0},
                null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, lado * 0.42f, halo);

        double ry = Math.toRadians(giroY), rx = Math.toRadians(inclinacion);
        float cosY = (float) Math.cos(ry), sinY = (float) Math.sin(ry);
        float cosX = (float) Math.cos(rx), sinX = (float) Math.sin(rx);
        float[] t = new float[3];

        int visibles = 0;
        for (Cara c : caras) {
            rotar(c.n, cosY, sinY, cosX, sinX, c.nr);
            float mx = 0, my = 0, mz = 0;
            for (int i = 0; i < 4; i++) {
                rotar(c.v[i], cosY, sinY, cosX, sinX, t);
                mx += t[0];
                my += t[1];
                mz += t[2];
                float w = t[2] + DISTANCIA;
                c.sx[i] = cx + f * t[0] / w;
                c.sy[i] = cy - f * t[1] / w;
            }
            mx /= 4;
            my /= 4;
            mz /= 4;
            // cara visible si su normal apunta hacia la cámara (situada en z = -DISTANCIA)
            if (c.nr[0] * mx + c.nr[1] * my + c.nr[2] * (mz + DISTANCIA) < 0) {
                c.profundidad = mz;
                orden[visibles++] = c;
            }
        }
        Arrays.sort(orden, 0, visibles, (a, b) -> Float.compare(b.profundidad, a.profundidad));

        for (int k = 0; k < visibles; k++) {
            Cara c = orden[k];
            float luz = Math.max(0f, c.nr[0] * LUZ[0] + c.nr[1] * LUZ[1] + c.nr[2] * LUZ[2]);
            float intensidad = 0.32f + 0.68f * luz;
            int base;
            if (c.material == NUCLEO) {
                base = colorNucleo;
                intensidad = 0.55f + 0.6f * luz; // el núcleo "emite" luz
            } else if (c.material == PIN) {
                base = 0xFFB6C2D4;
            } else {
                base = 0xFF1E3A8A;
            }
            relleno.setColor(sombrear(base, intensidad));
            if (c.material == NUCLEO && c.nr[1] > 0.5f) {
                relleno.setShadowLayer(lado * 0.06f, 0, 0, colorNucleo);
            } else {
                relleno.clearShadowLayer();
            }
            trazo.reset();
            trazo.moveTo(c.sx[0], c.sy[0]);
            for (int i = 1; i < 4; i++) {
                trazo.lineTo(c.sx[i], c.sy[i]);
            }
            trazo.close();
            canvas.drawPath(trazo, relleno);
            if (c.material == CUERPO) {
                canvas.drawPath(trazo, borde);
            }
        }

        if (animar && isShown()) {
            postInvalidateOnAnimation();
        } else {
            ultimoCuadro = 0;
        }
    }

    private static int sombrear(int color, float k) {
        return Color.rgb(Math.min(255, (int) (Color.red(color) * k)),
                Math.min(255, (int) (Color.green(color) * k)),
                Math.min(255, (int) (Color.blue(color) * k)));
    }

    private static int mezclar(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                toqueX = e.getX();
                toqueY = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                giroY += (e.getX() - toqueX) * 0.5f;
                inclinacion = Math.max(-85f, Math.min(-15f, inclinacion - (e.getY() - toqueY) * 0.3f));
                toqueX = e.getX();
                toqueY = e.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
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
