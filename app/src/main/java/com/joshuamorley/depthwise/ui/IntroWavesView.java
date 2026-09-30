package com.joshuamorley.depthwise.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * Start-up background: the app icon's depth bands, rising from the bottom and
 * rolling like swell. Drawn on the splash colour so it continues from the
 * system splash screen seamlessly.
 */
public class IntroWavesView extends View {

    /** Band colours from the app icon, deepest first. */
    private static final int[] COLORS = {0xFF15699F, 0xFF2A80B6, 0xFF4C9BCF};
    /** Where each band's crest sits, as a fraction of the height. */
    private static final float[] LEVELS = {0.70f, 0.79f, 0.88f};
    /** Speed and direction of each band, in wavelengths per loop. */
    private static final float[] SPEEDS = {1f, -1f, 2f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final ValueAnimator roll = ValueAnimator.ofFloat(0f, 1f);
    private final ValueAnimator rise = ValueAnimator.ofFloat(0f, 1f);
    private float phase, risen;

    public IntroWavesView(Context context) {
        this(context, null);
    }

    public IntroWavesView(Context context, AttributeSet attrs) {
        super(context, attrs);
        roll.setDuration(6000);
        roll.setRepeatCount(ValueAnimator.INFINITE);
        roll.setInterpolator(new LinearInterpolator());
        roll.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        rise.setDuration(900);
        rise.setInterpolator(new DecelerateInterpolator(2f));
        rise.addUpdateListener(a -> risen = (float) a.getAnimatedValue());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        roll.start();
        rise.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    public void stop() {
        roll.cancel();
        rise.cancel();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float amp = 12 * getResources().getDisplayMetrics().density;
        float wavelength = w / 1.3f;
        // Bands start below the screen and rise into place.
        float offset = (1f - risen) * h * 0.35f;
        for (int b = 0; b < COLORS.length; b++) {
            float base = h * LEVELS[b] + offset;
            float shift = (float) (phase * SPEEDS[b] * 2 * Math.PI);
            path.reset();
            path.moveTo(0, h);
            for (int x = 0; x <= w; x += 8) {
                double t = x / wavelength * 2 * Math.PI + shift + b;
                path.lineTo(x, (float) (base + Math.sin(t) * amp));
            }
            path.lineTo(w, h);
            path.close();
            paint.setColor(COLORS[b]);
            canvas.drawPath(path, paint);
        }
    }
}
