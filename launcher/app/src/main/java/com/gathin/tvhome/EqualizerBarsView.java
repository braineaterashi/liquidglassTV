package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * Aesthetic audio visualizer bars view for the Liquid Glass Mini Media Player.
 * Renders 4 glowing rounded pill bars that dance rhythmically to music beats when playing,
 * and settle to a calm idle baseline when paused.
 */
public class EqualizerBarsView extends View {

    private static final int BAR_COUNT = 4;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final float[] barHeights = new float[BAR_COUNT];
    private final float[] targetHeights = new float[BAR_COUNT];
    private final float[] frequencies = {1.8f, 2.7f, 2.2f, 3.1f};
    private final float[] phases = {0.0f, 1.2f, 2.5f, 0.7f};

    private ValueAnimator animator;
    private boolean isPlaying = false;
    private float animPhase = 0f;

    public EqualizerBarsView(Context context) {
        this(context, null);
    }

    public EqualizerBarsView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public EqualizerBarsView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < BAR_COUNT; i++) {
            barHeights[i] = 0.2f;
            targetHeights[i] = 0.2f;
        }
    }

    public void setPlaying(boolean playing) {
        if (this.isPlaying == playing) return;
        this.isPlaying = playing;
        if (playing) {
            startAnimation();
        } else {
            stopAnimation();
        }
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    private void startAnimation() {
        if (animator != null && animator.isRunning()) return;

        animator = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        animator.setDuration(1200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            animPhase = (float) animation.getAnimatedValue();
            updateBarHeights();
            invalidate();
        });
        animator.start();
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        // Smoothly settle bars to calm idle baseline
        for (int i = 0; i < BAR_COUNT; i++) {
            barHeights[i] = 0.2f;
        }
        invalidate();
    }

    private void updateBarHeights() {
        if (!isPlaying) return;
        for (int i = 0; i < BAR_COUNT; i++) {
            // Harmonic wave combination for organic, lively bounce
            float sin1 = (float) Math.sin(animPhase * frequencies[i] + phases[i]);
            float sin2 = (float) Math.sin(animPhase * 3.4f + phases[(i + 1) % BAR_COUNT]);
            float combined = 0.5f + 0.35f * sin1 + 0.15f * sin2;
            // Clamp between 0.18 and 1.0
            float clamped = Math.max(0.18f, Math.min(1.0f, combined));
            // Smooth interpolation
            barHeights[i] += (clamped - barHeights[i]) * 0.45f;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            // Neon cyan to radiant electric emerald gradient
            paint.setShader(new LinearGradient(
                    0, h, 0, 0,
                    new int[]{Color.parseColor("#00E5FF"), Color.parseColor("#10B981")},
                    null, Shader.TileMode.CLAMP
            ));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float totalGapRatio = 0.4f;
        float barWidth = (w * (1f - totalGapRatio)) / BAR_COUNT;
        float gap = (w * totalGapRatio) / (BAR_COUNT - 1);
        float radius = barWidth / 2f;

        for (int i = 0; i < BAR_COUNT; i++) {
            float left = i * (barWidth + gap);
            float right = left + barWidth;
            float currentH = Math.max(radius * 2f, h * barHeights[i]);
            float top = h - currentH;
            float bottom = h;

            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
