package com.aimouse;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 动态手势识别引擎 — 基于加速度时序特征实现手势识别。
 *
 * 支持的手势类型：
 *   1. SWIPE_HORIZONTAL — 快速水平甩动 → 映射为 Alt+Tab（窗口切换）
 *   2. SWIPE_VERTICAL   — 快速垂直甩动 → 映射为音量加减
 *   3. CIRCLE_CLOCKWISE — 顺时针画圈 → 映射为刷新(F5)或静音
 *   4. CIRCLE_CCW       — 逆时针画圈 → 映射为撤销(Ctrl+Z)或恢复
 *
 * 检测算法：
 *   - 甩动：基于滑动窗口内加速度峰值和方向判断
 *     水平甩动：|accel_x| > 14 m/s²，时间窗口 ≤ 150ms
 *     垂直甩动：|accel_y| > 14 m/s²，时间窗口 ≤ 150ms
 *   - 画圈：基于滑动窗口内 x/y 加速度的相位相关性
 */
public class GestureRecognitionEngine {

    // 手势类型枚举
    public enum GestureType {
        NONE,
        SWIPE_HORIZONTAL_LEFT,
        SWIPE_HORIZONTAL_RIGHT,
        SWIPE_VERTICAL_UP,
        SWIPE_VERTICAL_DOWN,
        CIRCLE_CLOCKWISE,
        CIRCLE_COUNTERCLOCKWISE
    }

    /**
     * 手势监听器接口 — 用户可自定义添加新手势处理逻辑。
     */
    public interface GestureListener {
        void onGestureDetected(GestureType gesture);
    }

    // 甩动检测参数
    private static final double SWIPE_ACCEL_THRESHOLD = 14.0;  // m/s²
    private static final long SWIPE_TIME_WINDOW_MS = 150;      // ms

    // 画圈检测参数
    private static final double CIRCLE_CORRELATION_THRESHOLD = 0.6;
    private static final int CIRCLE_WINDOW_SIZE = 30;           // 样本数

    // 滑动窗口
    private final LinkedList<AccelSample> swipeWindow;
    private final LinkedList<AccelSample> circleWindow;

    private final List<GestureListener> listeners;

    // 甩动检测状态
    private boolean swipeArmed;
    private double maxAccelMagnitude;
    private long swipeWindowStartTime;

    // 画圈检测状态
    private double prevPhase; // 用于跟踪相位变化方向

    /**
     * 加速度数据样本。
     */
    private static class AccelSample {
        final double ax, ay, az;
        final long timestamp;
        final double magnitude;

        AccelSample(double ax, double ay, double az, long timestamp) {
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.timestamp = timestamp;
            this.magnitude = Math.sqrt(ax * ax + ay * ay + az * az);
        }
    }

    public GestureRecognitionEngine() {
        this.swipeWindow = new LinkedList<>();
        this.circleWindow = new LinkedList<>();
        this.listeners = new ArrayList<>();
        this.swipeArmed = false;
        this.maxAccelMagnitude = 0.0;
        this.prevPhase = 0.0;
    }

    /**
     * 注册手势监听器。
     */
    public void addGestureListener(GestureListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * 移除手势监听器。
     */
    public void removeGestureListener(GestureListener listener) {
        listeners.remove(listener);
    }

    /**
     * 输入新的加速度数据，内部进行手势识别。
     *
     * @param ax X轴加速度 (m/s²)
     * @param ay Y轴加速度 (m/s²)
     * @param az Z轴加速度 (m/s²)
     * @param timestamp 时间戳 (ms)
     */
    public void feedAccelData(double ax, double ay, double az, long timestamp) {
        AccelSample sample = new AccelSample(ax, ay, az, timestamp);

        // === 甩动检测 ===
        detectSwipe(sample);

        // === 画圈检测 ===
        detectCircle(sample);
    }

    /**
     * 甩动检测逻辑。
     * 在滑动窗口内检测加速度峰值，满足阈值和时间窗口条件时触发。
     */
    private void detectSwipe(AccelSample sample) {
        // 清理过期样本
        while (!swipeWindow.isEmpty() &&
                sample.timestamp - swipeWindow.peekFirst().timestamp > SWIPE_TIME_WINDOW_MS) {
            swipeWindow.pollFirst();
        }

        swipeWindow.addLast(sample);

        if (!swipeArmed) {
            // 检测加速度是否超过阈值以"武装"检测
            if (Math.abs(sample.ax) > SWIPE_ACCEL_THRESHOLD ||
                    Math.abs(sample.ay) > SWIPE_ACCEL_THRESHOLD) {
                swipeArmed = true;
                maxAccelMagnitude = sample.magnitude;
                swipeWindowStartTime = sample.timestamp;
            }
        } else {
            // 已武装，追踪峰值
            if (sample.magnitude > maxAccelMagnitude) {
                maxAccelMagnitude = sample.magnitude;
            }

            // 窗口结束或加速度回落
            long elapsed = sample.timestamp - swipeWindowStartTime;
            boolean magnitudeFalling = sample.magnitude < maxAccelMagnitude * 0.5;

            if (elapsed >= SWIPE_TIME_WINDOW_MS || magnitudeFalling) {
                // 分析窗口内的主要方向
                double sumAx = 0, sumAy = 0;
                for (AccelSample s : swipeWindow) {
                    sumAx += s.ax;
                    sumAy += s.ay;
                }

                GestureType gesture = GestureType.NONE;

                if (Math.abs(sumAx) > Math.abs(sumAy)) {
                    // 水平甩动为主
                    gesture = sumAx > 0 ?
                            GestureType.SWIPE_HORIZONTAL_RIGHT :
                            GestureType.SWIPE_HORIZONTAL_LEFT;
                } else {
                    // 垂直甩动为主
                    gesture = sumAy > 0 ?
                            GestureType.SWIPE_VERTICAL_DOWN :
                            GestureType.SWIPE_VERTICAL_UP;
                }

                if (gesture != GestureType.NONE) {
                    notifyListeners(gesture);
                }

                // 重置
                swipeArmed = false;
                maxAccelMagnitude = 0.0;
                swipeWindow.clear();
            }
        }
    }

    /**
     * 画圈检测逻辑。
     * 通过分析 x/y 加速度的相位关系来判断旋转方向。
     */
    private void detectCircle(AccelSample sample) {
        // 移除重力分量后，只保留水平面上的加速度
        circleWindow.addLast(sample);

        // 保持窗口大小
        while (circleWindow.size() > CIRCLE_WINDOW_SIZE) {
            circleWindow.pollFirst();
        }

        if (circleWindow.size() < CIRCLE_WINDOW_SIZE) {
            return;
        }

        // 计算 x/y 加速度的互相关，判断旋转方向
        // 方法：计算相邻样本之间 (ax, ay) 向量的叉积符号
        int positiveCrossCount = 0;
        int negativeCrossCount = 0;
        int totalPairs = 0;

        AccelSample prev = null;
        for (AccelSample s : circleWindow) {
            if (prev != null) {
                // 二维叉积: prev_x * curr_y - prev_y * curr_x
                double cross = prev.ax * s.ay - prev.ay * s.ax;
                if (cross > 0.01) {
                    positiveCrossCount++;
                } else if (cross < -0.01) {
                    negativeCrossCount++;
                }
                totalPairs++;
            }
            prev = s;
        }

        if (totalPairs < 10) return;

        double consistency = (double) Math.max(positiveCrossCount, negativeCrossCount) / totalPairs;

        if (consistency > CIRCLE_CORRELATION_THRESHOLD) {
            GestureType gesture;
            if (positiveCrossCount > negativeCrossCount) {
                gesture = GestureType.CIRCLE_COUNTERCLOCKWISE;
            } else {
                gesture = GestureType.CIRCLE_CLOCKWISE;
            }
            notifyListeners(gesture);

            // 防止重复触发，清空窗口
            circleWindow.clear();
        }
    }

    /**
     * 通知所有注册的监听器。
     */
    private void notifyListeners(GestureType gesture) {
        for (GestureListener listener : listeners) {
            try {
                listener.onGestureDetected(gesture);
            } catch (Exception e) {
                // 防止监听器异常导致引擎崩溃
            }
        }
    }

    /**
     * 重置引擎状态。
     */
    public void reset() {
        swipeWindow.clear();
        circleWindow.clear();
        swipeArmed = false;
        maxAccelMagnitude = 0.0;
        prevPhase = 0.0;
    }
}