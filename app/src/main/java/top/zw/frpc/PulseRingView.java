package top.zw.frpc;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

public class PulseRingView extends View {

    private static final int RING_COUNT = 5;                // 5 simultaneous rings
    private static final int CYCLE_MS = 2000;                 // 2s full cycle
    private static final float MAX_RADIUS_RATIO = 1.2f;      // ring expands beyond view
    private static final int RING_COLOR = 0xFFFF69B4;        // hot pink
    private static final float STROKE_WIDTH = 3f;
    private static final int MAX_ALPHA = 160;
    private static final int GLOW_ALPHA_BASE = 15;
    private static final int GLOW_ALPHA_RANGE = 12;
    private static final long GLOW_PERIOD_MS = 1000;        // 1s — twice as slow as before

    private Paint[] ringPaints;
    private float[] ringPhases;
    private ValueAnimator animator;
    private boolean isRunning = false;

    public PulseRingView(Context context) {
        super(context);
        init();
    }

    public PulseRingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        ringPaints = new Paint[RING_COUNT];
        ringPhases = new float[RING_COUNT];
        for (int i = 0; i < RING_COUNT; i++) {
            ringPaints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringPaints[i].setStyle(Paint.Style.STROKE);
            ringPaints[i].setStrokeWidth(STROKE_WIDTH);
            ringPaints[i].setColor(RING_COLOR);
            ringPhases[i] = (float) i / RING_COUNT;
        }
    }

    public void startPulse() {
        if (isRunning) return;
        isRunning = true;
        setVisibility(View.VISIBLE);

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(CYCLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            for (int i = 0; i < RING_COUNT; i++) {
                ringPhases[i] = ((float) i / RING_COUNT + progress) % 1.0f;
            }
            invalidate();
        });
        animator.start();
    }

    public void stopPulse() {
        isRunning = false;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        setVisibility(View.GONE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isRunning) return;

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float maxR = Math.max(getWidth(), getHeight()) * MAX_RADIUS_RATIO;

        for (int i = 0; i < RING_COUNT; i++) {
            float phase = ringPhases[i];
            float radius = maxR * phase;
            int alpha = (int) (MAX_ALPHA * (1 - phase));
            if (alpha > 0 && radius > 2) {
                ringPaints[i].setAlpha(alpha);
                ringPaints[i].setStrokeWidth(STROKE_WIDTH * (1 + phase));
                canvas.drawCircle(cx, cy, radius, ringPaints[i]);
            }
        }

        // Center breathing glow — slowed to 1s period
        Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.FILL);
        int ca = GLOW_ALPHA_BASE + (int) (GLOW_ALPHA_RANGE * Math.sin(System.currentTimeMillis() / (double) GLOW_PERIOD_MS * 2 * Math.PI));
        glowPaint.setColor((ca << 24) | (RING_COLOR & 0x00FFFFFF));
        canvas.drawCircle(cx, cy, maxR * 0.08f, glowPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPulse();
    }
}
