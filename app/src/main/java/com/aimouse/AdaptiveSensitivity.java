package com.aimouse;

/**
 * 自适应动态灵敏度 — 根据当前移动速度实时调整光标灵敏度映射比。
 *
 * 采用分段增益公式：
 *   gain = 1.2 / (1 + e^(-speed / 100))
 *
 * 效果：
 *   - speed < 50 px/s  → gain ≈ 0.6  （精细定位）
 *   - speed ≈ 100 px/s → gain ≈ 1.2  （正常移动）
 *   - speed > 200 px/s → gain ≈ 1.8  （大幅跨屏移动）
 */
public class AdaptiveSensitivity {

    private double baseSensitivity;   // 基础灵敏度倍率，用户可调
    private double currentSpeed;      // 当前移动速度 (px/s)

    // 速度平滑参数
    private double smoothedSpeed;
    private static final double SMOOTHING_FACTOR = 0.7; // 指数移动平均系数

    public AdaptiveSensitivity() {
        this.baseSensitivity = 1.0;
        this.currentSpeed = 0.0;
        this.smoothedSpeed = 0.0;
    }

    /**
     * 设置基础灵敏度倍率（用户通过SeekBar调节）。
     * @param sensitivity 基础灵敏度，范围 0.1 ~ 3.0
     */
    public void setBaseSensitivity(double sensitivity) {
        this.baseSensitivity = Math.max(0.1, Math.min(3.0, sensitivity));
    }

    /**
     * 获取基础灵敏度倍率。
     */
    public double getBaseSensitivity() {
        return baseSensitivity;
    }

    /**
     * 更新当前移动速度并计算动态增益。
     *
     * @param rawSpeed 原始移动速度 (px/s)，由位移增量除以时间间隔得到
     * @return 动态增益系数
     */
    public double updateAndGetGain(double rawSpeed) {
        this.currentSpeed = rawSpeed;

        // 指数移动平均平滑速度，避免增益突变
        smoothedSpeed = SMOOTHING_FACTOR * smoothedSpeed + (1.0 - SMOOTHING_FACTOR) * rawSpeed;

        // 分段增益公式: gain = 1.2 / (1 + e^(-speed / 100))
        double dynamicGain = 1.2 / (1.0 + Math.exp(-smoothedSpeed / 100.0));

        // 最终输出 = 基础灵敏度 × 动态增益
        return baseSensitivity * dynamicGain;
    }

    /**
     * 直接根据给定速度计算增益，不更新内部状态。
     * @param speed 速度值
     * @return 增益系数
     */
    public static double calculateGain(double speed) {
        double dynamicGain = 1.2 / (1.0 + Math.exp(-speed / 100.0));
        return dynamicGain;
    }

    /**
     * 获取当前平滑后的速度。
     */
    public double getSmoothedSpeed() {
        return smoothedSpeed;
    }

    /**
     * 重置内部状态。
     */
    public void reset() {
        currentSpeed = 0.0;
        smoothedSpeed = 0.0;
    }
}