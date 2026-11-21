package com.aimouse;

/**
 * Mahony 六轴（陀螺仪 + 加速度计）四元数姿态融合 + 动态零偏校准。
 *
 * 为什么需要它：
 *   - 陀螺仪存在零偏（bias），直接积分会让光标持续漂移；
 *   - 加速度计可观测重力方向，但噪声大且对线性加速度敏感；
 *   - 单轴卡尔曼只能平滑位移，无法修正姿态漂移。
 *
 * 算法：
 *   1. 陀螺仪角速度积分四元数，得到姿态；
 *   2. 用加速度计测得的重力方向与四元数估计的重力方向做叉积，
 *      得到姿态误差，用比例项（Kp）快速纠偏，用积分项（Ki）估计零偏；
 *   3. 静止检测：陀螺仪模长足够小且加速度模长接近 1g 并持续一段时间时，
 *      用低通滤波进一步快速收敛三轴零偏（包含不可观测的偏航零偏）。
 *
 * 坐标系约定：
 *   - 四元数 q = (w, x, y, z) 表示“机体坐标系 → 世界坐标系”的旋转；
 *   - 世界坐标系 Z 轴朝上；
 *   - 加速度计为比力（静止时约 9.8 m/s²，屏幕朝上时 Z 为正）；
 *   - 陀螺仪单位为 rad/s，与 Android SensorManager 一致。
 *
 * 说明：没有磁力计时偏航角不可观测，偏航零偏只能依靠静止检测校准，
 * 这也是“动态零偏校准”必须存在的原因。
 */
public class SensorFusion {

    private static final double GRAVITY = 9.80665;

    // Mahony 增益（内部使用两倍系数，与原始论文公式保持一致）
    private static final double TWO_KP = 1.2;   // Kp = 0.6
    private static final double TWO_KI = 0.02;  // Ki = 0.01

    // 零偏限幅，避免异常样本把零偏推到不可恢复的值
    private static final double MAX_BIAS = 0.35; // rad/s ≈ 20°/s

    // 静止检测阈值
    private static final double STILL_GYRO_MAX = 0.10;      // rad/s ≈ 5.7°/s
    private static final double STILL_ACCEL_TOLERANCE = 0.5; // m/s²
    private static final double STILL_RAMP_SECONDS = 0.8;    // 静止多久后开始全速校准
    private static final double STATIC_ALPHA = 0.02;         // 静止时的低通系数
    private static final double CALIBRATED_SECONDS = 1.5;    // 判定“已校准”的静止时长

    // 姿态四元数（机体 → 世界）
    private double q0 = 1.0;
    private double q1 = 0.0;
    private double q2 = 0.0;
    private double q3 = 0.0;

    // 陀螺仪零偏估计（rad/s）
    private double biasX = 0.0;
    private double biasY = 0.0;
    private double biasZ = 0.0;

    // 最近一次加速度计数据
    private double accelX = 0.0;
    private double accelY = 0.0;
    private double accelZ = 0.0;
    private long lastAccelTimestamp = 0L;
    private boolean hasAccel = false;

    // 时间戳与静止检测状态
    private long lastGyroTimestamp = 0L;
    private double stillTime = 0.0;
    private boolean biasCalibrated = false;

    // 世界坐标系下的角速度（供光标映射使用）
    private double worldRateX = 0.0;
    private double worldRateY = 0.0;
    private double worldRateZ = 0.0;

    // 是否已用首帧加速度计完成初始姿态对齐
    private boolean attitudeInitialized = false;

    // Mahony 比例修正量（每次融合临时计算）
    private double correctionX = 0.0;
    private double correctionY = 0.0;
    private double correctionZ = 0.0;

    /**
     * 输入一帧陀螺仪数据并推进姿态融合。
     *
     * @param gx 机体 X 轴角速度 (rad/s)
     * @param gy 机体 Y 轴角速度 (rad/s)
     * @param gz 机体 Z 轴角速度 (rad/s)
     * @param timestampNs 传感器时间戳（纳秒，直接使用 SensorEvent.timestamp）
     */
    public void updateGyro(float gx, float gy, float gz, long timestampNs) {
        if (lastGyroTimestamp == 0L) {
            lastGyroTimestamp = timestampNs;
            updateStillness(gx, gy, gz, 0.02);
            return;
        }

        double dt = (timestampNs - lastGyroTimestamp) * 1e-9;
        // 时间戳跳变（首帧、后台恢复等）时使用默认 50Hz 步长
        if (dt <= 0.0 || dt > 0.2) {
            dt = 0.02;
        }
        lastGyroTimestamp = timestampNs;

        updateStillness(gx, gy, gz, dt);

        // 零偏补偿后的机体角速度
        double rateX = gx - biasX;
        double rateY = gy - biasY;
        double rateZ = gz - biasZ;

        if (isAccelFresh(timestampNs)) {
            correctWithAccel(dt);
            rateX += correctionX;
            rateY += correctionY;
            rateZ += correctionZ;
        }

        integrateQuaternion(rateX, rateY, rateZ, dt);
        updateWorldRates(rateX, rateY, rateZ);
    }

    /**
     * 输入一帧加速度计数据。
     *
     * @param ax X 轴加速度 (m/s²)
     * @param ay Y 轴加速度 (m/s²)
     * @param az Z 轴加速度 (m/s²)
     * @param timestampNs 传感器时间戳（纳秒）
     */
    public void updateAccel(float ax, float ay, float az, long timestampNs) {
        double norm = Math.sqrt(ax * ax + ay * ay + az * az);
        if (norm < 1e-3) {
            hasAccel = false;
            return;
        }
        accelX = ax;
        accelY = ay;
        accelZ = az;
        lastAccelTimestamp = timestampNs;
        hasAccel = true;

        if (!attitudeInitialized) {
            initializeAttitude(ax / norm, ay / norm, az / norm);
            attitudeInitialized = true;
        }
    }

    /**
     * 用首帧加速度计直接对齐横滚/俯仰，避免启动后姿态需要数秒才收敛。
     * 偏航角假设为 0（无磁力计不可观测）。
     */
    private void initializeAttitude(double ax, double ay, double az) {
        double roll = Math.atan2(ay, az);
        double pitch = Math.atan2(-ax, Math.sqrt(ay * ay + az * az));

        double cr = Math.cos(roll * 0.5);
        double sr = Math.sin(roll * 0.5);
        double cp = Math.cos(pitch * 0.5);
        double sp = Math.sin(pitch * 0.5);

        q0 = cr * cp;
        q1 = sr * cp;
        q2 = cr * sp;
        q3 = -sr * sp;
    }

    /**
     * Mahony 比例 + 积分反馈：用加速度计重力方向修正姿态并估计零偏。
     * 修正量写入 correctionX/Y/Z，由调用方与零偏补偿后的角速度相加后统一积分。
     */
    private void correctWithAccel(double dt) {
        correctionX = 0.0;
        correctionY = 0.0;
        correctionZ = 0.0;

        double norm = Math.sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ);
        // 剧烈线性加速度时重力方向不可信，跳过本次修正
        if (Math.abs(norm - GRAVITY) > 1.5) {
            return;
        }

        double ax = accelX / norm;
        double ay = accelY / norm;
        double az = accelZ / norm;

        // 四元数估计的机体坐标系重力方向（单位向量的一半）
        double halfVx = q1 * q3 - q0 * q2;
        double halfVy = q0 * q1 + q2 * q3;
        double halfVz = q0 * q0 - 0.5 + q3 * q3;

        // 测量重力方向与估计重力方向的叉积 → 姿态误差
        double halfEx = ay * halfVz - az * halfVy;
        double halfEy = az * halfVx - ax * halfVz;
        double halfEz = ax * halfVy - ay * halfVx;

        // 积分项 → 零偏估计（偏航不可观测，仅能靠静止校准）
        biasX = clampBias(biasX - TWO_KI * halfEx * dt);
        biasY = clampBias(biasY - TWO_KI * halfEy * dt);
        biasZ = clampBias(biasZ - TWO_KI * halfEz * dt);

        // 比例项直接修正角速度
        correctionX = TWO_KP * halfEx;
        correctionY = TWO_KP * halfEy;
        correctionZ = TWO_KP * halfEz;
    }

    /**
     * 静止检测 + 动态零偏校准。
     * 只有确认设备静止时才学习零偏，避免把真实运动误判成零偏。
     */
    private void updateStillness(double gx, double gy, double gz, double dt) {
        double gyroMagnitude = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (gyroMagnitude >= STILL_GYRO_MAX) {
            stillTime = 0.0;
            return;
        }

        if (hasAccel) {
            double accelNorm = Math.sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ);
            if (Math.abs(accelNorm - GRAVITY) > STILL_ACCEL_TOLERANCE) {
                stillTime = 0.0;
                return;
            }
        }

        stillTime += dt;

        // 静止初期缓慢介入，避免手指微抖被瞬间学进零偏
        double ramp = Math.min(1.0, stillTime / STILL_RAMP_SECONDS);
        double alpha = STATIC_ALPHA * ramp;

        biasX = clampBias(biasX + alpha * (gx - biasX));
        biasY = clampBias(biasY + alpha * (gy - biasY));
        biasZ = clampBias(biasZ + alpha * (gz - biasZ));

        if (stillTime >= CALIBRATED_SECONDS) {
            biasCalibrated = true;
        }
    }

    /**
     * 一阶龙格库塔积分四元数并归一化。
     */
    private void integrateQuaternion(double gx, double gy, double gz, double dt) {
        double halfDt = 0.5 * dt;

        double gxScaled = gx * halfDt;
        double gyScaled = gy * halfDt;
        double gzScaled = gz * halfDt;

        double qa = q0;
        double qb = q1;
        double qc = q2;

        q0 += -qb * gxScaled - qc * gyScaled - q3 * gzScaled;
        q1 += qa * gxScaled + qc * gzScaled - q3 * gyScaled;
        q2 += qa * gyScaled - qb * gzScaled + q3 * gxScaled;
        q3 += qa * gzScaled + qb * gyScaled - qc * gxScaled;

        double norm = Math.sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3);
        if (norm < 1e-9) {
            q0 = 1.0;
            q1 = q2 = q3 = 0.0;
            return;
        }
        q0 /= norm;
        q1 /= norm;
        q2 /= norm;
        q3 /= norm;
    }

    /**
     * 把机体角速度旋转到世界坐标系。
     * 这样无论手机怎么握持，光标映射都保持一致的手感：
     *   - worldRateZ（绕世界竖直轴）→ 水平光标
     *   - worldRateX（绕世界左右轴）→ 垂直光标
     */
    private void updateWorldRates(double wx, double wy, double wz) {
        // v' = v + 2 * qv × (qv × v + w * v)
        double qvx = q1, qvy = q2, qvz = q3, qw = q0;

        double cx = qvy * wz - qvz * wy;
        double cy = qvz * wx - qvx * wz;
        double cz = qvx * wy - qvy * wx;

        double ix = cx + qw * wx;
        double iy = cy + qw * wy;
        double iz = cz + qw * wz;

        worldRateX = wx + 2.0 * (qvy * iz - qvz * iy);
        worldRateY = wy + 2.0 * (qvz * ix - qvx * iz);
        worldRateZ = wz + 2.0 * (qvx * iy - qvy * ix);
    }

    private boolean isAccelFresh(long nowNs) {
        if (!hasAccel) return false;
        return (nowNs - lastAccelTimestamp) < 500_000_000L; // 500ms
    }

    private static double clampBias(double value) {
        if (value > MAX_BIAS) return MAX_BIAS;
        if (value < -MAX_BIAS) return -MAX_BIAS;
        return value;
    }

    /**
     * 重置时间戳与静止计时（传感器重新注册时调用），保留已学习的零偏。
     */
    public void resetTimestamp() {
        lastGyroTimestamp = 0L;
        lastAccelTimestamp = 0L;
        stillTime = 0.0;
    }

    /**
     * 完全重置姿态、零偏与计时。
     */
    public void reset() {
        q0 = 1.0;
        q1 = q2 = q3 = 0.0;
        biasX = biasY = biasZ = 0.0;
        hasAccel = false;
        biasCalibrated = false;
        attitudeInitialized = false;
        stillTime = 0.0;
        worldRateX = worldRateY = worldRateZ = 0.0;
        resetTimestamp();
    }

    // ===== 姿态与角速度查询 =====

    /** 世界坐标系 X 轴角速度 (rad/s)，用于垂直光标移动。 */
    public double getWorldRateX() {
        return worldRateX;
    }

    /** 世界坐标系 Y 轴角速度 (rad/s)。 */
    public double getWorldRateY() {
        return worldRateY;
    }

    /** 世界坐标系 Z 轴角速度 (rad/s)，用于水平光标移动。 */
    public double getWorldRateZ() {
        return worldRateZ;
    }

    public double getBiasX() {
        return biasX;
    }

    public double getBiasY() {
        return biasY;
    }

    public double getBiasZ() {
        return biasZ;
    }

    /** 是否已完成过至少一次静止零偏校准。 */
    public boolean isBiasCalibrated() {
        return biasCalibrated;
    }

    /** 当前姿态俯仰角（度）。 */
    public double getPitchDegrees() {
        double sinPitch = 2.0 * (q0 * q2 - q3 * q1);
        sinPitch = Math.max(-1.0, Math.min(1.0, sinPitch));
        return Math.toDegrees(Math.asin(sinPitch));
    }

    /** 当前姿态横滚角（度）。 */
    public double getRollDegrees() {
        return Math.toDegrees(Math.atan2(
                2.0 * (q0 * q1 + q2 * q3),
                1.0 - 2.0 * (q1 * q1 + q2 * q2)));
    }

    /** 当前姿态偏航角（度，无磁力计时会缓慢漂移）。 */
    public double getYawDegrees() {
        return Math.toDegrees(Math.atan2(
                2.0 * (q0 * q3 + q1 * q2),
                1.0 - 2.0 * (q2 * q2 + q3 * q3)));
    }
}
