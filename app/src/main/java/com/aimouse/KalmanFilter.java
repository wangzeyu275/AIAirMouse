package com.aimouse;

/**
 * 一维卡尔曼滤波器 — 用于对陀螺仪角速度积分后的位移增量进行平滑滤波。
 *
 * 数学模型（单轴）：
 *   状态 x： 平滑后的真实位移增量
 *   测量 z： 陀螺仪角速度积分得到的原始位移增量
 *
 * 预测方程：
 *   x̂_k|k-1 = x̂_{k-1|k-1}          （假设位移增量在短时间间隔内不变）
 *   P_k|k-1  = P_{k-1|k-1} + Q     （过程噪声累积）
 *
 * 更新方程：
 *   K_k      = P_k|k-1 / (P_k|k-1 + R)           （卡尔曼增益）
 *   x̂_k|k   = x̂_k|k-1 + K_k * (z_k - x̂_k|k-1)   （状态更新）
 *   P_k|k    = (1 - K_k) * P_k|k-1               （协方差更新）
 *
 * 推荐参数（50Hz 采样率）：
 *   过程噪声 Q = 0.01
 *   测量噪声 R = 0.1
 *
 * 200Hz 采样率时 R 降低至 0.05 以增加响应灵敏度。
 */
public class KalmanFilter {

    private double x;       // 状态估计值
    private double P;       // 估计误差协方差
    private double Q;       // 过程噪声协方差
    private double R;       // 测量噪声协方差
    private boolean initialized;

    /**
     * 构造卡尔曼滤波器。
     *
     * @param processNoise 过程噪声 Q，推荐 0.01
     * @param measurementNoise 测量噪声 R，推荐 0.1（50Hz）或 0.05（200Hz）
     */
    public KalmanFilter(double processNoise, double measurementNoise) {
        this.Q = processNoise;
        this.R = measurementNoise;
        this.x = 0.0;
        this.P = 1.0;  // 初始协方差，较大的值表示初始估计不确定
        this.initialized = false;
    }

    /**
     * 使用默认参数构造：Q=0.01, R=0.1（适用于50Hz采样率）
     */
    public KalmanFilter() {
        this(0.01, 0.1);
    }

    /**
     * 输入一个新的测量值，返回滤波后的估计值。
     *
     * @param measurement 原始测量值 z_k
     * @return 滤波后的状态估计值 x̂_k|k
     */
    public double update(double measurement) {
        if (!initialized) {
            // 首次测量直接初始化状态
            x = measurement;
            initialized = true;
            return x;
        }

        // ===== 预测阶段 =====
        // 状态预测：x̂_k|k-1 = x̂_{k-1|k-1}（假设位移增量短时间内不变）
        double xPredicted = x;

        // 协方差预测：P_k|k-1 = P_{k-1|k-1} + Q
        P = P + Q;

        // ===== 更新阶段 =====
        // 卡尔曼增益：K_k = P_k|k-1 / (P_k|k-1 + R)
        double K = P / (P + R);

        // 状态更新：x̂_k|k = x̂_k|k-1 + K_k * (z_k - x̂_k|k-1)
        x = xPredicted + K * (measurement - xPredicted);

        // 协方差更新：P_k|k = (1 - K_k) * P_k|k-1
        P = (1.0 - K) * P;

        return x;
    }

    /**
     * 重置滤波器状态。
     */
    public void reset() {
        x = 0.0;
        P = 1.0;
        initialized = false;
    }

    /**
     * 设置过程噪声 Q。
     * @param q 过程噪声值
     */
    public void setProcessNoise(double q) {
        this.Q = q;
    }

    /**
     * 设置测量噪声 R。
     * @param r 测量噪声值
     */
    public void setMeasurementNoise(double r) {
        this.R = r;
    }

    /**
     * 获取当前状态估计值。
     * @return 当前滤波后的值
     */
    public double getState() {
        return x;
    }

    /**
     * 获取当前估计误差协方差。
     * @return 协方差 P
     */
    public double getCovariance() {
        return P;
    }
}