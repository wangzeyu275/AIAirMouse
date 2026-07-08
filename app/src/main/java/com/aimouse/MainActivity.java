package com.aimouse;

import android.Manifest;
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
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

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
    private static final String PREFS_NAME = "aimouse_prefs";
    private static final String KEY_POINTER_ENABLED = "pointer_enabled";
    private static final String KEY_SENSITIVITY = "sensitivity";

    // ===== UI 组件 =====
    private TouchpadView touchpadView;
    private Switch switchPointer;
    private TextView tvConnectionStatus;
    private TextView tvGyroRaw;
    private TextView tvGyroFiltered;
    private TextView tvAccelRaw;
    private TextView tvGesture;
    private TextView tvSensitivityValue;
    private SeekBar seekBarSensitivity;
    private Button btnConnect;
    private View statusIndicator;

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

    // ===== 蓝牙HID服务 =====
    private MouseHIDService mouseHIDService;
    private boolean serviceBound = false;

    // ===== 传感器数据 =====
    private long lastSensorTimestamp = 0;
    private static final float NS2S = 1.0f / 1000000000.0f;

    // ===== UI更新 =====
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private static final long UI_UPDATE_INTERVAL_MS = 100; // 100ms更新一次UI文本

    // ===== 权限 =====
    private static final int REQUEST_BT_PERMISSIONS = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initSensors();
        initGestureEngine();
        loadPreferences();
        setupListeners();
        bindMouseHIDService();
        checkPermissions();
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
        tvSensitivityValue = findViewById(R.id.tvSensitivityValue);
        seekBarSensitivity = findViewById(R.id.seekBarSensitivity);
        btnConnect = findViewById(R.id.btnConnect);
        statusIndicator = findViewById(R.id.statusIndicator);

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
        int sensitivity = prefs.getInt(KEY_SENSITIVITY, 100);

        switchPointer.setChecked(pointerEnabled);
        touchpadView.setPointerEnabled(pointerEnabled);
        seekBarSensitivity.setProgress(sensitivity);
        updateSensitivityDisplay(sensitivity);
        adaptiveSensitivity.setBaseSensitivity(sensitivity / 100.0);
    }

    private void setupListeners() {
        // 指针移动开关
        switchPointer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            touchpadView.setPointerEnabled(isChecked);
            savePreference(KEY_POINTER_ENABLED, isChecked);
        });

        // 灵敏度滑块
        seekBarSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    updateSensitivityDisplay(progress);
                    adaptiveSensitivity.setBaseSensitivity(progress / 100.0);
                    savePreference(KEY_SENSITIVITY, progress);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 连接/断开按钮
        btnConnect.setOnClickListener(v -> {
            if (mouseHIDService != null) {
                if (mouseHIDService.isConnected()) {
                    mouseHIDService.disconnect();
                } else {
                    registerHIDWithCheck();
                }
            } else {
                registerHIDWithCheck();
            }
        });
    }

    private void updateSensitivityDisplay(int progress) {
        double value = progress / 100.0;
        tvSensitivityValue.setText(String.format(Locale.US, "%.1fx", value));
    }

    private void savePreference(String key, boolean value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(key, value).apply();
    }

    private void savePreference(String key, int value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putInt(key, value).apply();
    }

    // ===== 权限检查 =====

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 需要 BLUETOOTH_CONNECT 权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_ADVERTISE
                        },
                        REQUEST_BT_PERMISSIONS);
            }
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
            }
        }
    }

    // ===== 蓝牙HID服务绑定 =====

    private void bindMouseHIDService() {
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
            mouseHIDService = null;
            serviceBound = false;
        }
    };

    private void registerHIDWithCheck() {
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
        }
    }

    private void updateConnectionUI(boolean connected, BluetoothDevice device) {
        if (connected && device != null) {
            tvConnectionStatus.setText(String.format("已连接: %s", device.getName()));
            statusIndicator.setBackgroundResource(R.drawable.status_dot_connected);
            btnConnect.setText("断开连接");
        } else {
            tvConnectionStatus.setText("未连接");
            statusIndicator.setBackgroundResource(R.drawable.status_dot_disconnected);
            btnConnect.setText("注册HID设备");
        }
    }

    // ===== 传感器管理 =====

    @Override
    protected void onResume() {
        super.onResume();
        registerSensors();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 注意：为了让蓝牙服务在后台继续运行，此处不注销传感器
        // 传感器注册由前台服务保活
    }

    @Override
    protected void onDestroy() {
        unregisterSensors();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
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

        long currentTime = event.timestamp;

        if (lastSensorTimestamp != 0) {
            float dt = (currentTime - lastSensorTimestamp) * NS2S;

            // 限制最大时间间隔，防止异常
            if (dt > 0.5f) dt = 0.02f; // 默认50Hz
            if (dt <= 0) dt = 0.02f;

            // 将角速度积分得到角度变化，乘以灵敏度系数得到像素位移
            // 偏航(Yaw) → X轴鼠标移动，俯仰(Pitch) → Y轴鼠标移动
            float rawDisplacementX = gyroZ * dt * 1000f; // 缩放因子
            float rawDisplacementY = gyroX * dt * 1000f;

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

        // 输入手势识别引擎
        gestureEngine.feedAccelData(accelX, accelY, accelZ, event.timestamp / 1000000L);

        // 陀螺仪不可用时的降级模式：使用加速度计倾斜角度控制鼠标
        if (!gyroAvailable && mouseHIDService != null && mouseHIDService.isConnected()
                && isPointerEnabled()) {
            // 基于加速度计倾斜角度的简单映射
            // 过滤重力分量，得到设备倾斜
            float tiltX = (float) Math.atan2(accelX, Math.sqrt(accelY * accelY + accelZ * accelZ));
            float tiltY = (float) Math.atan2(accelY, Math.sqrt(accelX * accelX + accelZ * accelZ));

            double filteredX = kalmanX.update(tiltX * 20);
            double filteredY = kalmanY.update(tiltY * 20);

            double gain = adaptiveSensitivity.updateAndGetGain(10);
            int dx = (int) Math.round(filteredX * gain);
            int dy = (int) Math.round(filteredY * gain);

            if (dx != 0 || dy != 0) {
                mouseHIDService.moveCursor(dx, dy);
            }
        }

        // 限流UI更新
        if (event.timestamp - lastUIUpdateTime > UI_UPDATE_INTERVAL_MS * 1000000L) {
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

    // ===== 键盘快捷键模拟（通过HID发送按键组合） =====

    /**
     * 模拟键盘快捷键。
     * 注意：此方法通过蓝牙HID发送键盘报告，需要额外的键盘HID描述符。
     * 当前简化实现：通过Toast提示手势已触发。
     * 完整实现需要注册额外的键盘HID设备。
     */
    private void performKeyboardShortcut(String shortcut) {
        // TODO: 完整实现需要注册额外的HID键盘设备
        // 当前仅显示手势已触发
        showToast("手势触发: " + shortcut);
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
}