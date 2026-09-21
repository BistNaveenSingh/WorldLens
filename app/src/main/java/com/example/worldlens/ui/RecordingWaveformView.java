package com.example.worldlens.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * RecordingWaveformView:
 * Renders concentric soft pulse ripples and responsive audio waveform bars
 * for the World Lens press-and-hold microphone interaction.
 * Uses only native Android Canvas and the World Lens earth-tone palette.
 */
public class RecordingWaveformView extends View {

    // World Lens Palette: Section 19 Specification
    private static final int COLOR_PRIMARY = 0xFF9F7E69;   // Dusty Taupe
    private static final int COLOR_SECONDARY = 0xFFD2BBA0; // Pale Oak
    private static final int COLOR_HIGHLIGHT = 0xFFF2EFC7; // Lemon Chiffon
    private static final int COLOR_DARK = 0xFF3D2E24;      // Deep warm taupe

    private Paint ripplePaint;
    private Paint barPaint;
    private RectF barRect;

    private boolean isAnimating = false;
    private float animPhase = 0f;
    private float currentAmplitude = 0.2f; // [0.0 .. 1.0]
    private float targetAmplitude = 0.2f;
    private ValueAnimator animator;

    // Waveform bar configuration (7 bars)
    private static final int BAR_COUNT = 7;
    private final float[] barWeights = new float[]{0.4f, 0.7f, 1.0f, 0.85f, 1.0f, 0.7f, 0.4f};

    public RecordingWaveformView(Context context) {
        super(context);
        init();
    }

    public RecordingWaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RecordingWaveformView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ripplePaint.setStyle(Paint.Style.STROKE);

        barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setColor(COLOR_PRIMARY);

        barRect = new RectF();
    }

    public void startListening() {
        isAnimating = true;
        targetAmplitude = 0.35f;
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            animPhase = (float) animation.getAnimatedValue();
            // Smoothly ease amplitude
            currentAmplitude += (targetAmplitude - currentAmplitude) * 0.15f;
            invalidate();
        });
        animator.start();
        setVisibility(VISIBLE);
    }

    public void stopListening() {
        isAnimating = false;
        targetAmplitude = 0f;
        currentAmplitude = 0f;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        invalidate();
    }

    public void setRmsDb(float rmsDb) {
        // SpeechRecognizer rmsdB typically ranges from -2 to 10
        float normalized = (rmsDb + 2f) / 12f;
        if (normalized < 0.15f) normalized = 0.15f;
        if (normalized > 1.0f) normalized = 1.0f;
        targetAmplitude = normalized;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isAnimating && currentAmplitude <= 0.01f) {
            return;
        }

        float width = getWidth();
        float height = getHeight();
        float cx = width / 2f;
        float cy = height / 2f;
        float maxRadius = Math.min(width, height) * 0.46f;

        // 1. Draw 3 concentric expanding ripple rings
        for (int i = 0; i < 3; i++) {
            float phase = (animPhase + (i * 0.33f)) % 1.0f;
            float radius = (maxRadius * 0.35f) + (phase * (maxRadius * 0.65f));
            int alpha = (int) ((1.0f - phase) * 160);
            if (alpha < 0) alpha = 0;

            ripplePaint.setStrokeWidth(3f + (phase * 4f));
            if (i == 0) {
                ripplePaint.setColor(COLOR_PRIMARY);
            } else if (i == 1) {
                ripplePaint.setColor(COLOR_SECONDARY);
            } else {
                ripplePaint.setColor(COLOR_HIGHLIGHT);
            }
            ripplePaint.setAlpha(alpha);
            canvas.drawCircle(cx, cy, radius, ripplePaint);
        }

        // 2. Draw 7 animated soundwave bars at the center
        float barWidth = 6f * getResources().getDisplayMetrics().density;
        float barGap = 4f * getResources().getDisplayMetrics().density;
        float totalWaveWidth = (BAR_COUNT * barWidth) + ((BAR_COUNT - 1) * barGap);
        float startX = cx - (totalWaveWidth / 2f);
        float maxBarHeight = height * 0.42f;
        float minBarHeight = 8f * getResources().getDisplayMetrics().density;

        for (int i = 0; i < BAR_COUNT; i++) {
            // Procedural sine fluctuation modulated by currentAmplitude
            double wavePhase = (animPhase * 2 * Math.PI) + (i * 0.85);
            float waveFactor = (float) ((Math.sin(wavePhase) + 1.0) / 2.0);
            float effectiveAmp = (currentAmplitude * 0.7f) + (waveFactor * 0.3f);
            float barHeight = minBarHeight + (maxBarHeight - minBarHeight) * barWeights[i] * effectiveAmp;

            float left = startX + (i * (barWidth + barGap));
            float top = cy - (barHeight / 2f);
            float right = left + barWidth;
            float bottom = cy + (barHeight / 2f);

            barRect.set(left, top, right, bottom);
            barPaint.setColor(i == 2 || i == 4 ? COLOR_DARK : COLOR_PRIMARY);
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopListening();
    }
}
