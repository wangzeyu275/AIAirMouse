package com.aimouse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * 传感器实时波形图。
 * 以环形缓冲保存最近的移动强度，绘制带网格和渐变填充的平滑波浪轨迹。
 */
public class SensorWaveView extends View {

    private static final int CAPACITY = 160;
    private static final float MIN_DISPLAY_MAX = 0.18f;

    private final float[] samples = new float[CAPACITY];
    private int head = 0;
    private int count = 0;
    private float smoothed = 0f;
    private float displayMax = MIN_DISPLAY_MAX;
    private long lastFrameTime = 0;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();
    private final Path fillPath = new Path();

    public SensorWaveView(Context context) {
        this(context, null);
    }

    public SensorWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);

        gridPaint.setColor(0x1F4A90E2);
        gridPaint.setStrokeWidth(dp(0.7f));

        baselinePaint.setColor(0x334A90E2);
        baselinePaint.setStrokeWidth(dp(1f));

        glowPaint.setColor(0x3300E5FF);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(7f));
        glowPaint.setStrokeCap(Paint.Cap.ROUND);

        linePaint.setColor(0xFF00E5FF);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);

        dotPaint.setColor(0xFFB3F7FF);
        dotGlowPaint.setColor(0x4400E5FF);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /**
     * 追加一个 0~1 的移动强度采样。
     */
    public void pushSample(float value) {
        float v = value;
        if (v < 0f) v = 0f;
        if (v > 1f) v = 1f;

        smoothed = smoothed * 0.8f + v * 0.2f;
        samples[head] = smoothed;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) count++;

        displayMax = Math.max(MIN_DISPLAY_MAX,
                Math.max(displayMax * 0.994f, smoothed * 1.25f));

        long now = SystemClock.uptimeMillis();
        if (now - lastFrameTime >= 16) {
            lastFrameTime = now;
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fillPaint.setShader(new LinearGradient(0, 0, 0, h,
                0x3300E5FF, 0x0000E5FF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        float left = dp(2f);
        float right = width - dp(2f);
        float top = dp(4f);
        float bottom = height - dp(4f);

        // 科技感网格
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, gridPaint);
        }
        for (int i = 0; i <= 8; i++) {
            float x = left + (right - left) * i / 8f;
            canvas.drawLine(x, top, x, bottom, gridPaint);
        }

        float centerY = (top + bottom) / 2f;
        canvas.drawLine(left, centerY, right, centerY, baselinePaint);

        if (count < 2) return;

        int start = (head - count + CAPACITY) % CAPACITY;
        float stepX = (right - left) / (CAPACITY - 1);
        float offset = CAPACITY - count;
        float amplitude = (bottom - top) / 2f * 0.92f;

        float firstX = left + stepX * offset;
        float firstY = centerY - normalize(samples[start]) * amplitude;

        wavePath.reset();
        wavePath.moveTo(firstX, firstY);

        float prevX = firstX;
        float prevY = firstY;
        for (int i = 1; i < count; i++) {
            int index = (start + i) % CAPACITY;
            float x = left + stepX * (offset + i);
            float y = centerY - normalize(samples[index]) * amplitude;

            float midX = (prevX + x) / 2f;
            float midY = (prevY + y) / 2f;
            wavePath.quadTo(prevX, prevY, midX, midY);

            prevX = x;
            prevY = y;
        }
        wavePath.lineTo(prevX, prevY);

        fillPath.reset();
        fillPath.set(wavePath);
        fillPath.lineTo(prevX, bottom);
        fillPath.lineTo(firstX, bottom);
        fillPath.close();

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(wavePath, glowPaint);
        canvas.drawPath(wavePath, linePaint);

        // 末端光点
        canvas.drawCircle(prevX, prevY, dp(8f), dotGlowPaint);
        canvas.drawCircle(prevX, prevY, dp(2.5f), dotPaint);
    }

    private float normalize(float value) {
        float normalized = value / displayMax;
        return normalized > 1f ? 1f : normalized;
    }
}
