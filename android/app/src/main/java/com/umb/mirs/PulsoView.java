package com.umb.mirs;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/** Círculo con un signo de alerta y dos ondas que se expanden y se desvanecen sin parar. */
public class PulsoView extends View {

    private final Paint relleno = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint onda = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float fase = 0f;
    private int color;
    private ValueAnimator animador;

    public PulsoView(Context context) {
        this(context, null);
    }

    public PulsoView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        color = ContextCompat.getColor(context, R.color.peligro);
        relleno.setColor(color);
        onda.setColor(color);
        signo.setColor(0xFFFFFFFF);
        signo.setTextAlign(Paint.Align.CENTER);
        signo.setFakeBoldText(true);
    }

    public void setColor(int nuevo) {
        color = nuevo;
        relleno.setColor(nuevo);
        onda.setColor(nuevo);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animador = ValueAnimator.ofFloat(0f, 1f);
        animador.setDuration(1600);
        animador.setRepeatCount(ValueAnimator.INFINITE);
        animador.setInterpolator(new LinearInterpolator());
        animador.addUpdateListener(a -> {
            fase = (float) a.getAnimatedValue();
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
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float maximo = Math.min(cx, cy);
        float base = maximo * 0.5f;
        for (int i = 0; i < 2; i++) {
            float t = (fase + i * 0.5f) % 1f;
            onda.setAlpha((int) (110 * (1 - t)));
            canvas.drawCircle(cx, cy, base + (maximo - base) * t, onda);
        }
        canvas.drawCircle(cx, cy, base, relleno);
        signo.setTextSize(base * 1.2f);
        canvas.drawText("!", cx, cy - (signo.descent() + signo.ascent()) / 2, signo);
    }
}
