package com.aimouse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 虚拟触控板自定义View。
 *
 * 功能：
 *   - 绘制半透明矩形触控板区域 + 高亮发光边框
 *   - 单指滑动 → 移动鼠标指针（受 isPointerEnabled 控制）
 *   - 单指轻点 → 鼠标左键单击
 *   - 单指长按(>500ms) → 鼠标左键按住/拖拽
 *   - 双指上下滑动 → 垂直滚轮
 *   - 双指左右滑动 → 水平滚轮
 *   - 触摸面积防误触（面积 > 正常指尖1.5倍 → 忽略）
 */
public class TouchpadView extends View {

    // 绘制相关
    private final Paint fillPaint;
    private final Paint borderPaint;
    private final Paint glowPaint;
    private final RectF touchpadRect;
    private final RectF glowRect;

    // 触摸状态
    private boolean isPointerEnabled = true;  // 指针移动开关
    private TouchpadListener listener;

    // 单指状态
    private float lastX, lastY;
    private long touchDownTime;
    private boolean isLongPressTriggered;
    private boolean isDragging; // 是否正在拖拽（长按后移动）
    private static final long LONG_PRESS_DURATION_MS = 500;

    // 双指状态
    private boolean isTwoFinger;
    private float lastTwoFingerY;
    private float lastTwoFingerX;
    private static final float SCROLL_SCALE_FACTOR = 0.15f; // 滚轮缩放因子

    // 防误触
    private static final float NORMAL_FINGERTIP_SIZE = 100.0f; // 正常指尖触摸面积参考值 (px²)
    private static final float MAX_TOUCH_SIZE_RATIO = 1.5f;     // 面积超过此比例忽略

    // 长按检测Handler
    private final Handler longPressHandler;
    private final Runnable longPressRunnable;

    // 触控板占比
    private static final float TOUCHPAD_WIDTH_RATIO = 0.80f;  // 占据屏幕宽度的80%
    private static final float TOUCHPAD_HEIGHT_RATIO = 0.50f; // 占据屏幕高度的50%

    public TouchpadView(Context context) {
        this(context, null);
    }

    public TouchpadView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // 填充画笔
        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.parseColor("#334A90E2")); // 半透明蓝色

        // 边框画笔
        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3.0f);
        borderPaint.setColor(Color.parseColor("#884A90E2"));

        // 发光效果画笔
        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(8.0f);
        glowPaint.setColor(Color.parseColor("#1A4A90E2"));

        touchpadRect = new RectF();
        glowRect = new RectF();

        longPressHandler = new Handler(Looper.getMainLooper());
        longPressRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isLongPressTriggered && !isTwoFinger) {
                    isLongPressTriggered = true;
                    isDragging = true;
                    if (listener != null) {
                        listener.onLeftButtonDown();
                    }
                    // 震动反馈
                    performHapticFeedback(HAPTIC_FEEDBACK_ENABLED);
                }
            }
        };
    }

    /**
     * 设置触控板事件监听器。
     */
    public void setTouchpadListener(TouchpadListener listener) {
        this.listener = listener;
    }

    /**
     * 设置指针移动是否启用。
     */
    public void setPointerEnabled(boolean enabled) {
        this.isPointerEnabled = enabled;
        invalidate();
    }

    /**
     * 获取指针移动是否启用。
     */
    public boolean isPointerEnabled() {
        return isPointerEnabled;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        // 计算触控板区域（居中，80%宽度 × 50%高度）
        float padWidth = width * TOUCHPAD_WIDTH_RATIO;
        float padHeight = height * TOUCHPAD_HEIGHT_RATIO;
        float padLeft = (width - padWidth) / 2f;
        float padTop = (height - padHeight) / 2f;

        touchpadRect.set(padLeft, padTop, padLeft + padWidth, padTop + padHeight);

        float cornerRadius = 16f;

        // 发光效果（外扩）
        float glowPadding = 6f;
        glowRect.set(padLeft - glowPadding, padTop - glowPadding,
                padLeft + padWidth + glowPadding, padTop + padHeight + glowPadding);

        // 绘制发光层
        canvas.drawRoundRect(glowRect, cornerRadius + glowPadding, cornerRadius + glowPadding, glowPaint);

        // 绘制填充
        canvas.drawRoundRect(touchpadRect, cornerRadius, cornerRadius, fillPaint);

        // 绘制边框
        canvas.drawRoundRect(touchpadRect, cornerRadius, cornerRadius, borderPaint);

        // 绘制中心十字线（辅助定位）
        Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeWidth(1f);
        crossPaint.setColor(Color.parseColor("#224A90E2"));

        float cx = touchpadRect.centerX();
        float cy = touchpadRect.centerY();

        canvas.drawLine(cx, padTop + 20, cx, padTop + padHeight - 20, crossPaint);
        canvas.drawLine(padLeft + 20, cy, padLeft + padWidth - 20, cy, crossPaint);

        // 绘制状态文字
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#664A90E2"));
        textPaint.setTextSize(28f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        String statusText = isPointerEnabled ? "指针移动: 开" : "指针移动: 关 (仅点击/滚轮)";
        canvas.drawText(statusText, cx, padTop - 12f, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int pointerCount = event.getPointerCount();
        int actionMasked = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        // === 防误触：检查触摸面积 ===
        if (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            int idx = event.getActionIndex();
            float touchSize = event.getSize(idx);
            // getSize() 返回 0~1 范围，映射到近似面积
            float estimatedArea = touchSize * touchSize * 10000f; // 近似映射

            // 如果触摸面积超过正常指尖的1.5倍，忽略此触摸
            if (estimatedArea > NORMAL_FINGERTIP_SIZE * MAX_TOUCH_SIZE_RATIO) {
                // 大面积触摸（手掌等），忽略
                resetTouchState();
                return true; // 消费事件但不处理
            }
        }

        // === 双指处理 ===
        if (pointerCount == 2) {
            return handleTwoFingerTouch(event);
        }

        // === 单指处理 ===
        if (pointerCount > 2) {
            // 超过两指，忽略
            resetTouchState();
            return true;
        }

        return handleSingleFingerTouch(event);
    }

    /**
     * 单指触摸处理。
     */
    private boolean handleSingleFingerTouch(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        // 检查触摸是否在触控板区域内
        if (!touchpadRect.contains(x, y) && !touchpadRect.isEmpty()) {
            resetTouchState();
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = x;
                lastY = y;
                touchDownTime = System.currentTimeMillis();
                isLongPressTriggered = false;
                isDragging = false;

                // 启动长按检测
                longPressHandler.removeCallbacks(longPressRunnable);
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DURATION_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    // 长按拖拽模式：移动指针
                    if (isPointerEnabled && listener != null) {
                        float dx = x - lastX;
                        float dy = y - lastY;
                        listener.onPointerMove(dx, dy);
                    }
                    lastX = x;
                    lastY = y;
                } else if (!isLongPressTriggered) {
                    // 正常滑动模式
                    if (isPointerEnabled && listener != null) {
                        float dx = x - lastX;
                        float dy = y - lastY;
                        listener.onPointerMove(dx, dy);
                    }
                    // 如果移动距离超过阈值，取消长按检测
                    float totalDx = x - lastX;
                    float totalDy = y - lastY;
                    if (Math.abs(totalDx) > 5 || Math.abs(totalDy) > 5) {
                        longPressHandler.removeCallbacks(longPressRunnable);
                    }
                    lastX = x;
                    lastY = y;
                }
                return true;

            case MotionEvent.ACTION_UP:
                longPressHandler.removeCallbacks(longPressRunnable);

                long duration = System.currentTimeMillis() - touchDownTime;

                if (isDragging) {
                    // 结束拖拽
                    if (listener != null) {
                        listener.onLeftButtonUp();
                    }
                    isDragging = false;
                } else if (!isLongPressTriggered && duration < LONG_PRESS_DURATION_MS) {
                    // 短按 → 单击
                    if (listener != null) {
                        listener.onLeftButtonClick();
                    }
                } else if (isLongPressTriggered && duration >= LONG_PRESS_DURATION_MS) {
                    // 长按未移动 → 释放
                    if (listener != null) {
                        listener.onLeftButtonUp();
                    }
                }

                resetTouchState();
                return true;

            case MotionEvent.ACTION_CANCEL:
                longPressHandler.removeCallbacks(longPressRunnable);
                if (isDragging && listener != null) {
                    listener.onLeftButtonUp();
                }
                resetTouchState();
                return true;
        }

        return false;
    }

    /**
     * 双指触摸处理。
     * 双指上下滑动 → 垂直滚轮
     * 双指左右滑动 → 水平滚轮
     */
    private boolean handleTwoFingerTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() == 2) {
                    isTwoFinger = true;
                    isLongPressTriggered = false;
                    isDragging = false;
                    longPressHandler.removeCallbacks(longPressRunnable);

                    // 使用两个手指的平均位置
                    float avgY = (event.getY(0) + event.getY(1)) / 2f;
                    float avgX = (event.getX(0) + event.getX(1)) / 2f;
                    lastTwoFingerY = avgY;
                    lastTwoFingerX = avgX;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isTwoFinger && event.getPointerCount() == 2) {
                    float avgY = (event.getY(0) + event.getY(1)) / 2f;
                    float avgX = (event.getX(0) + event.getX(1)) / 2f;

                    float dy = lastTwoFingerY - avgY; // 注意：向上滑动为正（符合滚动直觉）
                    float dx = lastTwoFingerX - avgX;

                    if (listener != null) {
                        // 垂直滚动
                        if (Math.abs(dy) > 1f) {
                            int scrollAmount = Math.round(dy * SCROLL_SCALE_FACTOR);
                            if (scrollAmount != 0) {
                                listener.onVerticalScroll(scrollAmount);
                            }
                        }

                        // 水平滚动
                        if (Math.abs(dx) > 1f) {
                            int scrollAmount = Math.round(dx * SCROLL_SCALE_FACTOR);
                            if (scrollAmount != 0) {
                                listener.onHorizontalScroll(scrollAmount);
                            }
                        }
                    }

                    lastTwoFingerY = avgY;
                    lastTwoFingerX = avgX;
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                if (isTwoFinger) {
                    isTwoFinger = false;
                    resetTouchState();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                isTwoFinger = false;
                resetTouchState();
                return true;
        }

        return false;
    }

    /**
     * 重置触摸状态，防止状态残留导致异常。
     */
    private void resetTouchState() {
        longPressHandler.removeCallbacks(longPressRunnable);
        isLongPressTriggered = false;

        // 如果拖拽中意外重置，确保释放按键
        if (isDragging && listener != null) {
            listener.onLeftButtonUp();
        }
        isDragging = false;
        isTwoFinger = false;
    }

    /**
     * 触控板事件监听器接口。
     */
    public interface TouchpadListener {
        /**
         * 指针移动。
         * @param dx X轴位移增量 (像素)
         * @param dy Y轴位移增量 (像素)
         */
        void onPointerMove(float dx, float dy);

        /**
         * 左键单击。
         */
        void onLeftButtonClick();

        /**
         * 左键按下（拖拽开始）。
         */
        void onLeftButtonDown();

        /**
         * 左键释放（拖拽结束）。
         */
        void onLeftButtonUp();

        /**
         * 垂直滚轮。
         * @param amount 滚动量，正数向上，负数向下
         */
        void onVerticalScroll(int amount);

        /**
         * 水平滚轮。
         * @param amount 滚动量
         */
        void onHorizontalScroll(int amount);
    }
}