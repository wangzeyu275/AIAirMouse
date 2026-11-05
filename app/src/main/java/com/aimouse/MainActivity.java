package com.aimouse;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * AI空中鼠标主界面。
 *
 * 核心功能：
 *   - 屏幕中央半透明虚拟触控板
 *   - 指针移动开关（SharedPreferences 持久化）
 *   - 蓝牙HID连接管理
 *   - 陀螺仪+加速度计传感器数据采集与卡尔曼滤波
 *   - 自适应动态灵敏度
 *   - 手势识别引擎
 *   - 前台服务保活
 */
public class MainActivity extends AppCompatActivity
        implements SensorEventListener, TouchpadView.TouchpadListener {

    private static final String TAG = "MainActivity";
    static final String PREFS_NAME = "aimouse_prefs";
    static final String KEY_POINTER_ENABLED = "pointer_enabled";
    static final String KEY_SENSITIVITY = "sensitivity";
    static final String KEY_SHOW_WAVEFORM = "show_waveform";
    static final String KEY_GESTURE_ENABLED = "gesture_enabled";

    // ===== UI 组件 =====
    private TouchpadView touchpadView;
    private SwitchMaterial switchPointer;
    private SensorWaveView sensorWaveView;
    private TextView tvConnectionStatus;
    private TextView tvGyroRaw;
    private TextView tvGyroFiltered;
    private TextView tvAccelRaw;
    private TextView tvGesture;
    private TextView tvPointerState;
    private TextView btnLeftClick;
    private TextView btnRightClick;
    private Button btnConnect;
    private Button btnAdvanced;
    private ImageButton btnSettings;
    private View statusIndicator;
    private View pointerIndicator;
    private ValueAnimator pointerPulse;

    // ===== 传感器 =====
    private SensorManager sensorManager;
    private Sensor gyroSensor;
    private Sensor accelSensor;
    private boolean gyroAvailable = false;
    private boolean accelAvailable = false;

    // ===== 卡尔曼滤波器（X轴和Y轴各一个） =====
    private final KalmanFilter kalmanX = new KalmanFilter(0.01, 0.1);
    private final KalmanFilter kalmanY = new KalmanFilter(0.01, 0.1);

    // ===== 自适应灵敏度 =====
    private final AdaptiveSensitivity adaptiveSensitivity = new AdaptiveSensitivity();

    // ===== 手势识别引擎 =====
    private final GestureRecognitionEngine gestureEngine = new GestureRecognitionEngine();
    private boolean gestureEnabled = true;

    // ===== 显示设置 =====
    private boolean showWaveform = false;

    // ===== 蓝牙HID服务 =====
    private MouseHIDService mouseHIDService;
    private boolean serviceBound = false;
    private boolean serviceBinding = false;

    // ===== 传感器数据 =====
    private long lastSensorTimestamp = 0;
    private static final float NS2S = 1.0f / 1000000000.0f;

    // ===== UI更新 =====
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private static final long UI_UPDATE_INTERVAL_MS = 100; // 100ms更新一次UI文本

    // ===== 权限 =====
    private static final int REQUEST_BT_PERMISSIONS = 1001;

    // ===== Shizuku =====
    private Shizuku.OnRequestPermissionResultListener shizukuPermissionListener;
    private Shizuku.OnBinderReceivedListener shizukuBinderReceivedListener;
    private Shizuku.OnBinderDeadListener shizukuBinderDeadListener;
    private boolean shizukuListenersRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initSensors();
        initGestureEngine();
        loadPreferences();
        setupListeners();
        initShizukuListeners();
        checkPermissions();
        // Android 14 起 connectedDevice 前台服务要求已持有 BLUETOOTH_CONNECT，
        // 因此等蓝牙权限就绪后再启动服务
        if (hasBluetoothPermissions()) {
            bindMouseHIDService();
        }
    }

    // ===== 初始化 =====

    private void initViews() {
        touchpadView = findViewById(R.id.touchpadView);
        switchPointer = findViewById(R.id.switchPointer);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvGyroRaw = findViewById(R.id.tvGyroRaw);
        tvGyroFiltered = findViewById(R.id.tvGyroFiltered);
        tvAccelRaw = findViewById(R.id.tvAccelRaw);
        tvGesture = findViewById(R.id.tvGesture);
        btnConnect = findViewById(R.id.btnConnect);
        btnAdvanced = findViewById(R.id.btnAdvanced);
        btnSettings = findViewById(R.id.btnSettings);
        statusIndicator = findViewById(R.id.statusIndicator);
        sensorWaveView = findViewById(R.id.sensorWaveView);
        tvPointerState = findViewById(R.id.tvPointerState);
        pointerIndicator = findViewById(R.id.pointerIndicator);
        btnLeftClick = findViewById(R.id.btnLeftClick);
        btnRightClick = findViewById(R.id.btnRightClick);

        touchpadView.setTouchpadListener(this);
    }

    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) {
            showError("传感器服务不可用");
            return;
        }

        // 陀螺仪
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        gyroAvailable = (gyroSensor != null);

        // 加速度计
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        accelAvailable = (accelSensor != null);

        if (!gyroAvailable && !accelAvailable) {
            showError("设备无陀螺仪和加速度计，无法使用空中鼠标功能");
        } else if (!gyroAvailable) {
            // 陀螺仪不可用，降级为加速度计模式
            showToast("陀螺仪不可用，使用加速度计降级模式");
            tvGyroRaw.setText("陀螺仪: 不可用（加速度计降级模式）");
        }
    }

    private void initGestureEngine() {
        gestureEngine.addGestureListener(gesture -> {
            String gestureName;
            switch (gesture) {
                case SWIPE_HORIZONTAL_LEFT:
                    gestureName = "水平甩动(左) → Alt+Tab";
                    // 模拟 Alt+Tab
                    performKeyboardShortcut("Alt+Tab");
                    break;
                case SWIPE_HORIZONTAL_RIGHT:
                    gestureName = "水平甩动(右) → Alt+Tab";
                    performKeyboardShortcut("Alt+Tab");
                    break;
                case SWIPE_VERTICAL_UP:
                    gestureName = "垂直甩动(上) → 音量+";
                    performKeyboardShortcut("VolumeUp");
                    break;
                case SWIPE_VERTICAL_DOWN:
                    gestureName = "垂直甩动(下) → 音量-";
                    performKeyboardShortcut("VolumeDown");
                    break;
                case CIRCLE_CLOCKWISE:
                    gestureName = "顺时针画圈 → 刷新(F5)";
                    performKeyboardShortcut("F5");
                    break;
                case CIRCLE_COUNTERCLOCKWISE:
                    gestureName = "逆时针画圈 → 撤销(Ctrl+Z)";
                    performKeyboardShortcut("Ctrl+Z");
                    break;
                default:
                    gestureName = "未知";
                    break;
            }
            tvGesture.setText(String.format("手势: %s", gestureName));
            // 1.5秒后清除手势显示
            uiHandler.postDelayed(() -> tvGesture.setText("无手势"), 1500);
        });
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean pointerEnabled = prefs.getBoolean(KEY_POINTER_ENABLED, true);

        switchPointer.setChecked(pointerEnabled);
        touchpadView.setPointerEnabled(pointerEnabled);
        updatePointerUI(pointerEnabled);
        applySettings();
    }

    /**
     * 读取设置页保存的参数并在主界面生效。
     * 设置页返回后 onResume 会再次调用，确保改动即时可见。
     */
    private void applySettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        int sensitivity = prefs.getInt(KEY_SENSITIVITY, 100);
        adaptiveSensitivity.setBaseSensitivity(sensitivity / 100.0);

        showWaveform = prefs.getBoolean(KEY_SHOW_WAVEFORM, false);
        sensorWaveView.setVisibility(showWaveform ? View.VISIBLE : View.GONE);

        gestureEnabled = prefs.getBoolean(KEY_GESTURE_ENABLED, true);
        if (!gestureEnabled) {
            gestureEngine.reset();
            tvGesture.setText(R.string.gesture_disabled);
        } else {
            tvGesture.setText("手势: 无手势");
        }
    }

    private void setupListeners() {
        // 指针移动开关
        switchPointer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            touchpadView.setPointerEnabled(isChecked);
            updatePointerUI(isChecked);
            savePreference(KEY_POINTER_ENABLED, isChecked);
        });

        // 设置入口
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // 连接/断开/取消注册按钮
        btnConnect.setOnClickListener(v -> {
            if (mouseHIDService == null) {
                registerHIDWithCheck();
                return;
            }
            if (mouseHIDService.isConnected()) {
                mouseHIDService.disconnect();
            } else if (mouseHIDService.isRegistered()) {
                // 已注册但尚未连接 → 取消注册
                mouseHIDService.unregisterHID();
                updateConnectionUI(false, null);
            } else {
                registerHIDWithCheck();
            }
        });

        // 底部弧形左右键
        btnLeftClick.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            handleMouseButton(false);
        });
        btnRightClick.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            handleMouseButton(true);
        });

        // 高级选项：Shizuku 授权（可选，不影响常规 HID 注册）
        btnAdvanced.setOnClickListener(v -> showAdvancedOptionsDialog());
    }

    private void updatePointerUI(boolean enabled) {
        if (pointerPulse != null) {
            pointerPulse.cancel();
            pointerPulse = null;
        }
        pointerIndicator.setAlpha(1f);
        pointerIndicator.setScaleX(1f);
        pointerIndicator.setScaleY(1f);
        pointerIndicator.setBackgroundResource(enabled
                ? R.drawable.pointer_indicator_on
                : R.drawable.pointer_indicator_off);
        tvPointerState.setText(enabled
                ? "ON · 陀螺仪控制已开启"
                : "OFF · 指针移动已暂停");
        tvPointerState.setTextColor(ContextCompat.getColor(this,
                enabled ? R.color.neonCyan : R.color.textSecondary));

        if (enabled) {
            // 呼吸灯效果，强化“已开启”的状态感
            pointerPulse = ValueAnimator.ofFloat(0.55f, 1f);
            pointerPulse.setDuration(1400);
            pointerPulse.setRepeatMode(ValueAnimator.REVERSE);
            pointerPulse.setRepeatCount(ValueAnimator.INFINITE);
            pointerPulse.setInterpolator(new AccelerateDecelerateInterpolator());
            pointerPulse.addUpdateListener(animation -> {
                float value = (float) animation.getAnimatedValue();
                pointerIndicator.setAlpha(value);
                pointerIndicator.setScaleX(value);
                pointerIndicator.setScaleY(value);
            });
            pointerPulse.start();
        }
    }

    private void handleMouseButton(boolean right) {
        if (mouseHIDService != null && mouseHIDService.isConnected()) {
            if (right) {
                mouseHIDService.clickRight();
            } else {
                mouseHIDService.clickLeft();
            }
        } else {
            showToast("蓝牙未连接");
        }
    }

    private void savePreference(String key, boolean value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(key, value).apply();
    }

    // ===== 权限检查 =====

    /**
     * 初始化 Shizuku 监听器。
     * 当 Shizuku 服务启动/停止时自动处理权限授权流程。
     */
    private void initShizukuListeners() {
        shizukuBinderReceivedListener = () ->
                Log.i(TAG, "Shizuku 服务已连接（可选，HID 注册不再依赖它）");

        shizukuBinderDeadListener = () -> {
            Log.i(TAG, "Shizuku 服务已断开");
        };

        shizukuPermissionListener = (requestCode, grantResult) -> {
            Log.i(TAG, "Shizuku 权限请求结果: " + grantResult);
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(this::grantPrivilegeWithCallback);
            } else {
                runOnUiThread(() -> showToast("Shizuku 权限被拒绝（不影响常规 HID 使用）"));
            }
        };

        Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener);
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener);
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        shizukuListenersRegistered = true;
    }

    private void grantPrivilegeWithCallback() {
        ShizukuHelper.grantPrivilegePermission(new ShizukuHelper.GrantCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> showToast("BLUETOOTH_PRIVILEGED 授权成功"));
            }

            @Override
            public void onFailure(String reason) {
                runOnUiThread(() -> showToast("授权失败: " + reason));
            }

            @Override
            public void onShizukuNotAvailable() {
                runOnUiThread(() -> showToast("Shizuku 未运行"));
            }
        });
    }

    private void checkPermissions() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_BT_PERMISSIONS + 1);
            }
        }
    }

    /**
     * HID 注册只依赖运行时蓝牙权限（与 Bluke 的实现一致），
     * 不再把 signature 级的 BLUETOOTH_PRIVILEGED 作为前置条件。
     */
    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_ADVERTISE
                    },
                    REQUEST_BT_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BT_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                showToast("蓝牙权限被拒绝，部分功能可能不可用");
            } else {
                // 权限授予后再启动 HID 前台服务
                bindMouseHIDService();
            }
        }
    }

    // ===== 蓝牙HID服务绑定 =====

    private void bindMouseHIDService() {
        if (serviceBound || serviceBinding) {
            return;
        }
        serviceBinding = true;
        Intent intent = new Intent(this, MouseHIDService.class);
        // 启动前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MouseHIDService.MouseHIDBinder binder = (MouseHIDService.MouseHIDBinder) service;
            mouseHIDService = binder.getService();
            serviceBound = true;
            serviceBinding = false;

            mouseHIDService.setConnectionCallback(new MouseHIDService.ConnectionCallback() {
                @Override
                public void onConnectionStateChanged(boolean connected, BluetoothDevice device) {
                    runOnUiThread(() -> updateConnectionUI(connected, device));
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> showError(errorMessage));
                }
            });

            // 自动注册HID
            registerHIDWithCheck();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (mouseHIDService != null) {
                mouseHIDService.setConnectionCallback(null);
            }
            mouseHIDService = null;
            serviceBound = false;
            serviceBinding = false;
        }
    };

    private void registerHIDWithCheck() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }
        if (mouseHIDService == null) {
            showToast("蓝牙服务未就绪，请稍候…");
            return;
        }

        // 确保蓝牙已开启
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            showToast("请先开启蓝牙");
            if (adapter != null) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivity(enableBtIntent);
            }
            return;
        }

        boolean success = mouseHIDService.registerHID();
        if (success) {
            updateConnectionUI(false, null);
            tvConnectionStatus.setText("等待电脑连接…");
            statusIndicator.setBackgroundResource(R.drawable.status_dot_connecting);
            btnConnect.setText("取消注册");
        } else {
            showToast("HID 注册失败：请确认设备支持蓝牙 HID Device，且蓝牙权限已授予");
        }
    }

    private void updateConnectionUI(boolean connected, BluetoothDevice device) {
        if (connected && device != null) {
            String deviceName = getSafeDeviceName(device);
            tvConnectionStatus.setText(String.format("已连接: %s", deviceName));
            statusIndicator.setBackgroundResource(R.drawable.status_dot_connected);
            btnConnect.setText("断开连接");
        } else {
            tvConnectionStatus.setText("未连接");
            statusIndicator.setBackgroundResource(R.drawable.status_dot_disconnected);
            btnConnect.setText("注册HID设备");
        }
    }

    private String getSafeDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name != null ? name : "未知设备";
        } catch (SecurityException e) {
            // 蓝牙权限可能被撤销，读取设备名会抛 SecurityException
            return "未知设备";
        }
    }

    // ===== 传感器管理 =====

    @Override
    protected void onResume() {
        super.onResume();
        registerSensors();
        // 从设置页返回时同步最新配置
        if (sensorWaveView != null) {
            applySettings();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 注意：为了让蓝牙服务在后台继续运行，此处不注销传感器
        // 传感器注册由前台服务保活
    }

    @Override
    protected void onDestroy() {
        if (pointerPulse != null) {
            pointerPulse.cancel();
            pointerPulse = null;
        }
        unregisterSensors();
        if (serviceBound) {
            if (mouseHIDService != null) {
                mouseHIDService.setConnectionCallback(null);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
        mouseHIDService = null;
        // 移除 Shizuku 监听器
        if (shizukuListenersRegistered) {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener);
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener);
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
            shizukuListenersRegistered = false;
        }
        super.onDestroy();
    }

    private void registerSensors() {
        if (sensorManager == null) return;

        if (gyroAvailable) {
            sensorManager.registerListener(this, gyroSensor,
                    SensorManager.SENSOR_DELAY_GAME); // 50Hz (20ms)
        }

        if (accelAvailable) {
            sensorManager.registerListener(this, accelSensor,
                    SensorManager.SENSOR_DELAY_GAME);
        }
    }

    private void unregisterSensors() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    // ===== SensorEventListener 实现 =====

    private long lastUIUpdateTime = 0;
    private float lastGyroX = 0, lastGyroY = 0, lastGyroZ = 0;
    private float lastAccelX = 0, lastAccelY = 0, lastAccelZ = 0;

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            handleGyroData(event);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            handleAccelData(event);
        }
    }

    /**
     * 处理陀螺仪数据。
     * 将角速度 (rad/s) 积分得到角度变化，转换为鼠标位移增量。
     */
    private void handleGyroData(SensorEvent event) {
        // 陀螺仪数据: values[0]=绕X轴角速度, values[1]=绕Y轴角速度, values[2]=绕Z轴角速度 (rad/s)
        float gyroX = event.values[0]; // 俯仰
        float gyroY = event.values[1]; // 翻滚
        float gyroZ = event.values[2]; // 偏航

        lastGyroX = gyroX;
        lastGyroY = gyroY;
        lastGyroZ = gyroZ;

        // 波形图：角速度幅值归一化（约 4 rad/s 视为满幅）
        if (showWaveform) {
            float gyroMagnitude = (float) Math.sqrt(
                    gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ);
            sensorWaveView.pushSample(gyroMagnitude / 4.0f);
        }

        long currentTime = event.timestamp;

        if (lastSensorTimestamp != 0) {
            float dt = (currentTime - lastSensorTimestamp) * NS2S;

            // 限制最大时间间隔，防止异常
            if (dt > 0.5f) dt = 0.02f; // 默认50Hz
            if (dt <= 0) dt = 0.02f;

            // 将角速度积分得到角度变化，乘以灵敏度系数得到像素位移
            // 偏航(Yaw) → X轴鼠标移动，俯仰(Pitch) → Y轴鼠标移动
            // 手机移动方向与光标方向相反，X/Y 位移取反修正
            float rawDisplacementX = -gyroZ * dt * 1000f; // 缩放因子
            float rawDisplacementY = -gyroX * dt * 1000f;

            // 卡尔曼滤波平滑
            double filteredX = kalmanX.update(rawDisplacementX);
            double filteredY = kalmanY.update(rawDisplacementY);

            // 计算速度
            double speed = Math.sqrt(
                    rawDisplacementX * rawDisplacementX + rawDisplacementY * rawDisplacementY
            ) / Math.max(dt, 0.001f);

            // 自适应灵敏度增益
            double gain = adaptiveSensitivity.updateAndGetGain(speed);

            // 最终位移
            int dx = (int) Math.round(filteredX * gain);
            int dy = (int) Math.round(filteredY * gain);

            // 发送到蓝牙HID（如果已连接）
            if (mouseHIDService != null && mouseHIDService.isConnected() && isPointerEnabled()) {
                if (dx != 0 || dy != 0) {
                    mouseHIDService.moveCursor(dx, dy);
                }
            }

            // 限流UI更新
            if (currentTime - lastUIUpdateTime > UI_UPDATE_INTERVAL_MS * 1000000L) {
                lastUIUpdateTime = currentTime;
                final float finalGyroX = gyroX;
                final float finalGyroY = gyroY;
                final float finalGyroZ = gyroZ;
                final double finalFilteredX = filteredX;
                final double finalFilteredY = filteredY;
                runOnUiThread(() -> {
                    tvGyroRaw.setText(String.format(Locale.US,
                            "原始: X=%.3f Y=%.3f Z=%.3f rad/s",
                            finalGyroX, finalGyroY, finalGyroZ));
                    tvGyroFiltered.setText(String.format(Locale.US,
                            "滤波后: X=%.2f Y=%.2f (增益: %.2fx)",
                            finalFilteredX, finalFilteredY, gain));
                });
            }
        }

        lastSensorTimestamp = currentTime;
    }

    /**
     * 处理加速度计数据。
     * 用于手势识别，以及陀螺仪不可用时的降级模式。
     */
    private void handleAccelData(SensorEvent event) {
        // 加速度计数据: values[0]=X轴加速度, values[1]=Y轴加速度, values[2]=Z轴加速度 (m/s²)
        float accelX = event.values[0];
        float accelY = event.values[1];
        float accelZ = event.values[2];

        lastAccelX = accelX;
        lastAccelY = accelY;
        lastAccelZ = accelZ;

        // 波形图：相对重力的偏移量归一化（约 6 m/s² 视为满幅）
        if (showWaveform) {
            float accelMagnitude = (float) Math.sqrt(
                    accelX * accelX + accelY * accelY + accelZ * accelZ);
            sensorWaveView.pushSample(Math.abs(accelMagnitude - 9.8f) / 6.0f);
        }

        // 输入手势识别引擎（关闭时彻底跳过，避免误触发快捷键）
        if (gestureEnabled) {
            gestureEngine.feedAccelData(accelX, accelY, accelZ, event.timestamp / 1000000L);
        }

        // 陀螺仪不可用时的降级模式：使用加速度计倾斜角度控制鼠标
        if (!gyroAvailable && mouseHIDService != null && mouseHIDService.isConnected()
                && isPointerEnabled()) {
            // 基于加速度计倾斜角度的简单映射
            // 过滤重力分量，得到设备倾斜
            float tiltX = (float) Math.atan2(accelX, Math.sqrt(accelY * accelY + accelZ * accelZ));
            float tiltY = (float) Math.atan2(accelY, Math.sqrt(accelX * accelX + accelZ * accelZ));

            double filteredX = kalmanX.update(-tiltX * 20);
            double filteredY = kalmanY.update(-tiltY * 20);

            double gain = adaptiveSensitivity.updateAndGetGain(10);
            int dx = (int) Math.round(filteredX * gain);
            int dy = (int) Math.round(filteredY * gain);

            if (dx != 0 || dy != 0) {
                mouseHIDService.moveCursor(dx, dy);
            }
        }

        // 限流UI更新
        if (event.timestamp - lastUIUpdateTime > UI_UPDATE_INTERVAL_MS * 1000000L) {
            lastUIUpdateTime = event.timestamp;
            final float finalAccelX = accelX;
            final float finalAccelY = accelY;
            final float finalAccelZ = accelZ;
            runOnUiThread(() -> {
                tvAccelRaw.setText(String.format(Locale.US,
                        "原始: X=%.2f Y=%.2f Z=%.2f m/s²",
                        finalAccelX, finalAccelY, finalAccelZ));
            });
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 传感器精度变化，一般不需要特殊处理
    }

    // ===== TouchpadView.TouchpadListener 实现 =====

    @Override
    public void onPointerMove(float dx, float dy) {
        if (mouseHIDService != null && mouseHIDService.isConnected()) {
            // 触控板位移映射到鼠标位移
            int moveX = Math.round(dx);
            int moveY = Math.round(dy);
            if (moveX != 0 || moveY != 0) {
                mouseHIDService.moveCursor(moveX, moveY);
            }
        }
    }

    @Override
    public void onLeftButtonClick() {
        if (mouseHIDService != null && mouseHIDService.isConnected()) {
            mouseHIDService.clickLeft();
        }
    }

    @Override
    public void onLeftButtonDown() {
        if (mouseHIDService != null && mouseHIDService.isConnected()) {
            mouseHIDService.leftButtonDown();
        }
    }

    @Override
    public void onLeftButtonUp() {
        if (mouseHIDService != null && mouseHIDService.isConnected()) {
            mouseHIDService.leftButtonUp();
        }
    }

    @Override
    public void onVerticalScroll(int amount) {
        if (mouseHIDService != null && mouseHIDService.isConnected()) {
            mouseHIDService.scrollVertical(amount);
        }
    }

    @Override
    public void onHorizontalScroll(int amount) {
        if (mouseHIDService != null && mouseHIDService.isConnected()) {
            mouseHIDService.scrollHorizontal(amount);
        }
    }

    // ===== 键盘快捷键模拟（通过 HID 键盘报告发送真实按键组合） =====

    /**
     * 通过蓝牙 HID 键盘发送真实的按键组合。
     * 支持的快捷键: Alt+Tab, Ctrl+Z, F5, Escape, VolumeUp, VolumeDown, VolumeMute
     */
    private void performKeyboardShortcut(String shortcut) {
        if (mouseHIDService != null && mouseHIDService.isConnected()) {
            mouseHIDService.sendShortcut(shortcut);
            showToast("已发送: " + shortcut);
        } else {
            showToast("手势触发: " + shortcut + " (蓝牙未连接)");
        }
    }

    // ===== 辅助方法 =====

    private boolean isPointerEnabled() {
        return switchPointer.isChecked();
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void showError(String message) {
        Log.e(TAG, message);
        new AlertDialog.Builder(this)
                .setTitle("错误")
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show();
    }

    /**
     * 高级选项：Shizuku 授权（可选）。
     * 常规 HID 注册只需运行时蓝牙权限；Shizuku 仅用于个别需要系统级权限的 ROM。
     */
    private void showAdvancedOptionsDialog() {
        boolean installed = ShizukuHelper.isShizukuInstalled(this);
        boolean available = ShizukuHelper.isShizukuAvailable();
        boolean shizukuGranted = ShizukuHelper.hasShizukuPermission();
        boolean privileged = ShizukuHelper.isPrivilegeGranted(this);

        String message = "Shizuku 是可选的高级功能，常规蓝牙鼠标/键盘无需它。\n\n"
                + "当前状态\n"
                + "· Shizuku 已安装：" + (installed ? "是" : "否") + "\n"
                + "· Shizuku 运行中：" + (available ? "是" : "否") + "\n"
                + "· Shizuku 授权：" + (shizukuGranted ? "已授权" : "未授权") + "\n"
                + "· BLUETOOTH_PRIVILEGED：" + (privileged ? "已授予" : "未授予") + "\n\n"
                + "说明：只有部分 ROM 在 HID 注册失败时，才建议尝试补充系统级权限。";

        new AlertDialog.Builder(this)
                .setTitle("高级选项")
                .setMessage(message)
                .setPositiveButton("开始 Shizuku 授权", (d, w) -> {
                    if (!available) {
                        // 未安装或未运行 Shizuku 时，由 ShizukuHelper 给出安装/启动引导
                        ShizukuHelper.startGrantFlow(this, null);
                    } else if (!ShizukuHelper.hasShizukuPermission()) {
                        try {
                            Shizuku.requestPermission(0);
                        } catch (Exception e) {
                            showToast("Shizuku 请求失败: " + e.getMessage());
                        }
                    } else {
                        grantPrivilegeWithCallback();
                    }
                })
                .setNeutralButton("ADB 命令", (d, w) -> new AlertDialog.Builder(this)
                        .setTitle("手动 ADB 授权（可选）")
                        .setMessage("adb shell pm grant com.aimouse "
                                + "android.permission.BLUETOOTH_PRIVILEGED\n\n"
                                + "多数设备无需执行此步骤。")
                        .setPositiveButton("确定", null)
                        .show())
                .setNegativeButton("关闭", null)
                .show();
    }
}