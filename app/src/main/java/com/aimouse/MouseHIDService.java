package com.aimouse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 蓝牙 HID 复合设备服务 — 手机同时模拟蓝牙鼠标 + 键盘。
 *
 * 使用 Android 官方 BluetoothHidDevice API，通过复合 HID 报告描述符
 * 在单个 HID 设备中注册鼠标和键盘两个子设备，通过 Report ID 区分：
 *   - Report ID 0x01: 鼠标（5 按钮 + X/Y 位移 + 垂直/水平滚轮）
 *   - Report ID 0x02: 标准键盘（8 修饰键 + 6 键位同时按下）
 */
public class MouseHIDService extends Service {

    private static final String TAG = "MouseHIDService";
    private static final String CHANNEL_ID = "mouse_hid_channel";
    private static final int NOTIFICATION_ID = 1;

    // ===== Report ID 常量 =====
    private static final byte REPORT_ID_MOUSE = 0x01;
    private static final byte REPORT_ID_KEYBOARD = 0x02;

    // ===== 复合 HID 报告描述符（鼠标 + 键盘） =====
    //
    // 鼠标报告 (6 字节 = Report ID + 5 字节数据):
    //   Byte 0: Report ID (0x01)
    //   Byte 1: 按钮位 [0:左键, 1:右键, 2:中键, 3:按钮4, 4:按钮5, 5-7:填充]
    //   Byte 2: X 轴相对位移 (signed 8-bit, -127~127)
    //   Byte 3: Y 轴相对位移 (signed 8-bit, -127~127)
    //   Byte 4: 垂直滚轮 (signed 8-bit, -127~127)
    //   Byte 5: 水平滚轮/AC Pan (signed 8-bit, -127~127)
    //
    // 键盘报告 (9 字节 = Report ID + 8 字节数据):
    //   Byte 0: Report ID (0x02)
    //   Byte 1: 修饰键位图 [0:LCtrl, 1:LShift, 2:LAlt, 3:LGUI, 4:RCtrl, 5:RShift, 6:RAlt, 7:RGUI]
    //   Byte 2: 保留 (0x00)
    //   Bytes 3-8: 同时按下的键位码 (最多 6 键，0x00 表示未按下)
    private static final byte[] HID_REPORT_DESCRIPTOR = {
            // ===== Report ID 0x01: Mouse =====
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x02,        // Usage (Mouse)
            (byte) 0xA1, 0x01, // Collection (Application)
            (byte) 0x85, 0x01, //   Report ID (1)
            0x09, 0x01,        //   Usage (Pointer)
            (byte) 0xA1, 0x00, //   Collection (Physical)
            // --- 按钮 ---
            0x05, 0x09,        //     Usage Page (Button)
            0x19, 0x01,        //     Usage Minimum (1)
            0x29, 0x05,        //     Usage Maximum (5)
            0x15, 0x00,        //     Logical Minimum (0)
            0x25, 0x01,        //     Logical Maximum (1)
            (byte) 0x95, 0x05, //     Report Count (5)
            0x75, 0x01,        //     Report Size (1)
            (byte) 0x81, 0x02, //     Input (Data,Var,Abs) — 5 button bits
            // --- 填充 ---
            (byte) 0x95, 0x01, //     Report Count (1)
            0x75, 0x03,        //     Report Size (3)
            (byte) 0x81, 0x03, //     Input (Cnst,Var,Abs) — 3 bits padding
            // --- X, Y, Wheel ---
            0x05, 0x01,        //     Usage Page (Generic Desktop)
            0x09, 0x30,        //     Usage (X)
            0x09, 0x31,        //     Usage (Y)
            0x09, 0x38,        //     Usage (Wheel)
            0x15, (byte) 0x81, //     Logical Minimum (-127)
            0x25, 0x7F,        //     Logical Maximum (127)
            0x75, 0x08,        //     Report Size (8)
            (byte) 0x95, 0x03, //     Report Count (3)
            (byte) 0x81, 0x06, //     Input (Data,Var,Rel) — X, Y, Wheel
            (byte) 0xC0,       //   End Collection
            // --- AC Pan (水平滚轮) ---
            0x05, 0x0C,        //   Usage Page (Consumer)
            0x0A, 0x38, 0x02,  //   Usage (AC Pan)
            0x15, (byte) 0x81, //   Logical Minimum (-127)
            0x25, 0x7F,        //   Logical Maximum (127)
            0x75, 0x08,        //   Report Size (8)
            (byte) 0x95, 0x01, //   Report Count (1)
            (byte) 0x81, 0x06, //   Input (Data,Var,Rel)
            (byte) 0xC0,       // End Collection (Mouse)

            // ===== Report ID 0x02: Keyboard =====
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x06,        // Usage (Keyboard)
            (byte) 0xA1, 0x01, // Collection (Application)
            (byte) 0x85, 0x02, //   Report ID (2)
            // --- 修饰键 (8 bits) ---
            0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
            0x19, (byte) 0xE0, //   Usage Minimum (224) — Left Control
            0x29, (byte) 0xE7, //   Usage Maximum (231) — Right GUI
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x01,        //   Logical Maximum (1)
            0x75, 0x01,        //   Report Size (1)
            (byte) 0x95, 0x08, //   Report Count (8)
            (byte) 0x81, 0x02, //   Input (Data,Var,Abs) — 8 modifier bits
            // --- 保留字节 ---
            (byte) 0x95, 0x01, //   Report Count (1)
            0x75, 0x08,        //   Report Size (8)
            (byte) 0x81, 0x03, //   Input (Cnst,Var,Abs) — Reserved
            // --- 6 键位 ---
            (byte) 0x95, 0x06, //   Report Count (6)
            0x75, 0x08,        //   Report Size (8)
            0x15, 0x00,        //   Logical Minimum (0)
            0x26, (byte) 0xFF, 0x00, // Logical Maximum (255)
            0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
            0x19, 0x00,        //   Usage Minimum (0)
            0x2A, (byte) 0xFF, 0x00, // Usage Maximum (255)
            (byte) 0x81, 0x00, //   Input (Data,Array)
            (byte) 0xC0         // End Collection (Keyboard)
    };

    // ===== USB HID 键位码常量 =====
    public static final byte KEY_A = 0x04;
    public static final byte KEY_B = 0x05;
    public static final byte KEY_C = 0x06;
    public static final byte KEY_D = 0x07;
    public static final byte KEY_E = 0x08;
    public static final byte KEY_F = 0x09;
    public static final byte KEY_G = 0x0A;
    public static final byte KEY_H = 0x0B;
    public static final byte KEY_I = 0x0C;
    public static final byte KEY_J = 0x0D;
    public static final byte KEY_K = 0x0E;
    public static final byte KEY_L = 0x0F;
    public static final byte KEY_M = 0x10;
    public static final byte KEY_N = 0x11;
    public static final byte KEY_O = 0x12;
    public static final byte KEY_P = 0x13;
    public static final byte KEY_Q = 0x14;
    public static final byte KEY_R = 0x15;
    public static final byte KEY_S = 0x16;
    public static final byte KEY_T = 0x17;
    public static final byte KEY_U = 0x18;
    public static final byte KEY_V = 0x19;
    public static final byte KEY_W = 0x1A;
    public static final byte KEY_X = 0x1B;
    public static final byte KEY_Y = 0x1C;
    public static final byte KEY_Z = 0x1D;
    public static final byte KEY_1 = 0x1E;
    public static final byte KEY_2 = 0x1F;
    public static final byte KEY_3 = 0x20;
    public static final byte KEY_4 = 0x21;
    public static final byte KEY_5 = 0x22;
    public static final byte KEY_6 = 0x23;
    public static final byte KEY_7 = 0x24;
    public static final byte KEY_8 = 0x25;
    public static final byte KEY_9 = 0x26;
    public static final byte KEY_0 = 0x27;
    public static final byte KEY_ENTER = 0x28;
    public static final byte KEY_ESCAPE = 0x29;
    public static final byte KEY_BACKSPACE = 0x2A;
    public static final byte KEY_TAB = 0x2B;
    public static final byte KEY_SPACE = 0x2C;
    public static final byte KEY_MINUS = 0x2D;
    public static final byte KEY_EQUAL = 0x2E;
    public static final byte KEY_LEFTBRACE = 0x2F;
    public static final byte KEY_RIGHTBRACE = 0x30;
    public static final byte KEY_BACKSLASH = 0x31;
    public static final byte KEY_SEMICOLON = 0x33;
    public static final byte KEY_APOSTROPHE = 0x34;
    public static final byte KEY_GRAVE = 0x35;
    public static final byte KEY_COMMA = 0x36;
    public static final byte KEY_DOT = 0x37;
    public static final byte KEY_SLASH = 0x38;
    public static final byte KEY_CAPSLOCK = 0x39;
    public static final byte KEY_F1 = 0x3A;
    public static final byte KEY_F2 = 0x3B;
    public static final byte KEY_F3 = 0x3C;
    public static final byte KEY_F4 = 0x3D;
    public static final byte KEY_F5 = 0x3E;
    public static final byte KEY_F6 = 0x3F;
    public static final byte KEY_F7 = 0x40;
    public static final byte KEY_F8 = 0x41;
    public static final byte KEY_F9 = 0x42;
    public static final byte KEY_F10 = 0x43;
    public static final byte KEY_F11 = 0x44;
    public static final byte KEY_F12 = 0x45;
    public static final byte KEY_INSERT = 0x49;
    public static final byte KEY_HOME = 0x4A;
    public static final byte KEY_PAGEUP = 0x4B;
    public static final byte KEY_DELETE = 0x4C;
    public static final byte KEY_END = 0x4D;
    public static final byte KEY_PAGEDOWN = 0x4E;
    public static final byte KEY_RIGHT = 0x4F;
    public static final byte KEY_LEFT = 0x50;
    public static final byte KEY_DOWN = 0x51;
    public static final byte KEY_UP = 0x52;
    public static final byte KEY_VOLUME_UP = (byte) 0x80;   // Consumer page
    public static final byte KEY_VOLUME_DOWN = (byte) 0x81; // Consumer page
    public static final byte KEY_VOLUME_MUTE = (byte) 0x7F; // Consumer page

    // 修饰键位掩码
    public static final byte MOD_LEFT_CTRL   = 0x01;
    public static final byte MOD_LEFT_SHIFT  = 0x02;
    public static final byte MOD_LEFT_ALT    = 0x04;
    public static final byte MOD_LEFT_GUI    = 0x08;
    public static final byte MOD_RIGHT_CTRL  = 0x10;
    public static final byte MOD_RIGHT_SHIFT = 0x20;
    public static final byte MOD_RIGHT_ALT   = 0x40;
    public static final byte MOD_RIGHT_GUI   = (byte) 0x80;

    // ===== 蓝牙相关 =====
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice connectedDevice;
    private boolean isRegistered = false;
    private boolean isConnected = false;

    // ===== 后台线程 =====
    private HandlerThread handlerThread;
    private Handler backgroundHandler;
    private ExecutorService reportExecutor;

    // ===== 当前 HID 报告状态 =====
    private final byte[] mouseReport = new byte[6];    // 6 字节鼠标报告 (含 Report ID)
    private final byte[] keyboardReport = new byte[9]; // 9 字节键盘报告 (含 Report ID)
    private final Object reportLock = new Object();

    // ===== 连接状态回调 =====
    private ConnectionCallback connectionCallback;

    // ===== Binder =====
    private final IBinder binder = new MouseHIDBinder();

    public interface ConnectionCallback {
        void onConnectionStateChanged(boolean connected, BluetoothDevice device);
        void onError(String errorMessage);
    }

    public class MouseHIDBinder extends Binder {
        public MouseHIDService getService() {
            return MouseHIDService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("等待连接…"));

        // 初始化后台线程
        handlerThread = new HandlerThread("MouseHIDThread");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());
        reportExecutor = Executors.newSingleThreadExecutor();

        // 初始化报告 ID
        mouseReport[0] = REPORT_ID_MOUSE;
        keyboardReport[0] = REPORT_ID_KEYBOARD;

        // 初始化蓝牙
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "设备不支持蓝牙");
            if (connectionCallback != null) {
                connectionCallback.onError("设备不支持蓝牙");
            }
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(bluetoothStateReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        unregisterHID();
        unregisterReceiver(bluetoothStateReceiver);
        reportExecutor.shutdown();
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        super.onDestroy();
    }

    public void setConnectionCallback(ConnectionCallback callback) {
        this.connectionCallback = callback;
    }

    // ===== HID 注册/注销 =====

    public boolean registerHID() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "蓝牙不可用");
            return false;
        }
        try {
            bluetoothAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    hidDevice = (BluetoothHidDevice) proxy;
                    BluetoothHidDeviceAppSdpSettings sdpSettings =
                            new BluetoothHidDeviceAppSdpSettings(
                                    "AI Air Mouse", "AI空中鼠标 (鼠标+键盘)",
                                    "Android", BluetoothHidDevice.SUBCLASS1_COMBO,
                                    HID_REPORT_DESCRIPTOR);
                    BluetoothHidDeviceAppQosSettings qosSettings =
                            new BluetoothHidDeviceAppQosSettings(
                                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                                    800, 0, 0, BluetoothHidDeviceAppQosSettings.MAX);
                    boolean result = hidDevice.registerApp(
                            sdpSettings, null, qosSettings,
                            Runnable::run, hidDeviceCallback);
                    if (result) {
                        isRegistered = true;
                        Log.i(TAG, "HID 复合设备注册成功 (鼠标+键盘)");
                        updateNotification("等待蓝牙配对…");
                    } else {
                        Log.e(TAG, "HID 设备注册失败");
                        if (connectionCallback != null) {
                            connectionCallback.onError("HID 设备注册失败");
                        }
                    }
                }
                @Override
                public void onServiceDisconnected(int profile) {
                    hidDevice = null;
                    isRegistered = false;
                }
            }, BluetoothProfile.HID_DEVICE);
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "缺少 BLUETOOTH_PRIVILEGED 权限: " + e.getMessage());
            if (connectionCallback != null) {
                connectionCallback.onError(
                        "缺少 BLUETOOTH_PRIVILEGED 权限\n请通过 Shizuku 或 ADB 授权");
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "HID 注册异常: " + e.getMessage(), e);
            if (connectionCallback != null) {
                connectionCallback.onError("HID 注册异常: " + e.getMessage());
            }
            return false;
        }
    }

    public void unregisterHID() {
        try {
            if (hidDevice != null && isRegistered) {
                if (isConnected && connectedDevice != null) {
                    hidDevice.disconnect(connectedDevice);
                }
                hidDevice.unregisterApp();
                isRegistered = false;
                isConnected = false;
                connectedDevice = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "注销 HID 异常: " + e.getMessage(), e);
        }
    }

    public void disconnect() {
        try {
            if (hidDevice != null && isConnected && connectedDevice != null) {
                hidDevice.disconnect(connectedDevice);
            }
        } catch (Exception e) {
            Log.e(TAG, "断开连接异常: " + e.getMessage(), e);
        }
    }

    public boolean isConnected() { return isConnected; }
    public boolean isRegistered() { return isRegistered; }

    // ================================================================
    //  鼠标操作接口
    // ================================================================

    public void moveCursor(int dx, int dy) {
        synchronized (reportLock) {
            mouseReport[2] = (byte) clampToByte(dx);  // X (skip Report ID at [0])
            mouseReport[3] = (byte) clampToByte(dy);  // Y
        }
        sendMouseReport();
    }

    public void clickLeft() {
        setMouseButton(0, true);
        sendMouseReport();
        backgroundHandler.postDelayed(() -> {
            setMouseButton(0, false);
            sendMouseReport();
        }, 50);
    }

    public void leftButtonDown() {
        setMouseButton(0, true);
        sendMouseReport();
    }

    public void leftButtonUp() {
        setMouseButton(0, false);
        sendMouseReport();
    }

    public void clickRight() {
        setMouseButton(1, true);
        sendMouseReport();
        backgroundHandler.postDelayed(() -> {
            setMouseButton(1, false);
            sendMouseReport();
        }, 50);
    }

    public void clickMiddle() {
        setMouseButton(2, true);
        sendMouseReport();
        backgroundHandler.postDelayed(() -> {
            setMouseButton(2, false);
            sendMouseReport();
        }, 50);
    }

    public void scrollVertical(int amount) {
        synchronized (reportLock) {
            mouseReport[4] = (byte) clampToByte(amount);
        }
        sendMouseReport();
        backgroundHandler.postDelayed(() -> {
            synchronized (reportLock) { mouseReport[4] = 0; }
            sendMouseReport();
        }, 30);
    }

    public void scrollHorizontal(int amount) {
        synchronized (reportLock) {
            mouseReport[5] = (byte) clampToByte(amount);
        }
        sendMouseReport();
        backgroundHandler.postDelayed(() -> {
            synchronized (reportLock) { mouseReport[5] = 0; }
            sendMouseReport();
        }, 30);
    }

    public void resetMouseReport() {
        synchronized (reportLock) {
            Arrays.fill(mouseReport, 1, mouseReport.length, (byte) 0);
        }
        sendMouseReport();
    }

    // ================================================================
    //  键盘操作接口
    // ================================================================

    /**
     * 发送单个按键（按下后立即释放）。
     */
    public void pressAndReleaseKey(byte keyCode) {
        pressKey(keyCode, (byte) 0);
        backgroundHandler.postDelayed(() -> releaseAllKeys(), 60);
    }

    /**
     * 发送带修饰键的按键组合（按下后立即释放）。
     * 例如：pressAndReleaseKeyCombo(KEY_TAB, MOD_LEFT_ALT) → Alt+Tab
     */
    public void pressAndReleaseKeyCombo(byte keyCode, byte modifiers) {
        pressKey(keyCode, modifiers);
        backgroundHandler.postDelayed(() -> releaseAllKeys(), 80);
    }

    /**
     * 按下按键（不释放，用于组合键）。
     */
    public void pressKey(byte keyCode, byte modifiers) {
        synchronized (reportLock) {
            keyboardReport[1] = modifiers;  // 修饰键 (skip Report ID at [0])
            keyboardReport[2] = 0x00;       // 保留
            keyboardReport[3] = keyCode;    // 键位 1
            keyboardReport[4] = 0x00;       // 键位 2-6 清零
            keyboardReport[5] = 0x00;
            keyboardReport[6] = 0x00;
            keyboardReport[7] = 0x00;
            keyboardReport[8] = 0x00;
        }
        sendKeyboardReport();
    }

    /**
     * 释放所有键盘按键。
     */
    public void releaseAllKeys() {
        synchronized (reportLock) {
            Arrays.fill(keyboardReport, 1, keyboardReport.length, (byte) 0);
        }
        sendKeyboardReport();
    }

    /**
     * 发送预定义的快捷键。
     */
    public void sendShortcut(String shortcut) {
        switch (shortcut) {
            case "Alt+Tab":
                pressKey(KEY_TAB, MOD_LEFT_ALT);
                backgroundHandler.postDelayed(() -> releaseAllKeys(), 80);
                break;
            case "Ctrl+Z":
                pressKey(KEY_Z, MOD_LEFT_CTRL);
                backgroundHandler.postDelayed(() -> releaseAllKeys(), 80);
                break;
            case "Ctrl+C":
                pressKey(KEY_C, MOD_LEFT_CTRL);
                backgroundHandler.postDelayed(() -> releaseAllKeys(), 80);
                break;
            case "Ctrl+V":
                pressKey(KEY_V, MOD_LEFT_CTRL);
                backgroundHandler.postDelayed(() -> releaseAllKeys(), 80);
                break;
            case "Ctrl+A":
                pressKey(KEY_A, MOD_LEFT_CTRL);
                backgroundHandler.postDelayed(() -> releaseAllKeys(), 80);
                break;
            case "Ctrl+S":
                pressKey(KEY_S, MOD_LEFT_CTRL);
                backgroundHandler.postDelayed(() -> releaseAllKeys(), 80);
                break;
            case "F5":
                pressAndReleaseKey(KEY_F5);
                break;
            case "Escape":
                pressAndReleaseKey(KEY_ESCAPE);
                break;
            case "VolumeUp":
                pressAndReleaseKey(KEY_VOLUME_UP);
                break;
            case "VolumeDown":
                pressAndReleaseKey(KEY_VOLUME_DOWN);
                break;
            case "VolumeMute":
                pressAndReleaseKey(KEY_VOLUME_MUTE);
                break;
            default:
                Log.w(TAG, "未知快捷键: " + shortcut);
                break;
        }
    }

    // ===== 私有方法 =====

    private void setMouseButton(int buttonIndex, boolean pressed) {
        synchronized (reportLock) {
            if (pressed) {
                mouseReport[1] |= (byte) (1 << buttonIndex);
            } else {
                mouseReport[1] &= (byte) ~(1 << buttonIndex);
            }
        }
    }

    private void sendMouseReport() {
        sendReport(mouseReport);
    }

    private void sendKeyboardReport() {
        sendReport(keyboardReport);
    }

    private void sendReport(byte[] report) {
        if (!isConnected || hidDevice == null || connectedDevice == null) {
            return;
        }
        reportExecutor.execute(() -> {
            try {
                byte[] data;
                synchronized (reportLock) {
                    data = report.clone();
                }
                // sendReport(device, reportType, data)
                // reportType: 0=INPUT, 1=OUTPUT, 2=FEATURE
                hidDevice.sendReport(connectedDevice, 0, data);
            } catch (Exception e) {
                Log.e(TAG, "发送 HID 报告失败: " + e.getMessage());
            }
        });
    }

    private static int clampToByte(int value) {
        return Math.max(-127, Math.min(127, value));
    }

    // ===== 通知 =====

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_channel_desc));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        Notification notification = buildNotification(text);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification);
    }

    // ===== BluetoothHidDevice 回调 =====

    private final BluetoothHidDevice.Callback hidDeviceCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice device, boolean registered) {
            Log.i(TAG, "HID App 状态变更: registered=" + registered);
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                connectedDevice = device;
                updateNotification("已连接到 " + device.getName());
                if (connectionCallback != null)
                    connectionCallback.onConnectionStateChanged(true, device);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                connectedDevice = null;
                updateNotification("连接已断开");
                if (connectionCallback != null)
                    connectionCallback.onConnectionStateChanged(false, device);
            }
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hidDevice != null) {
                synchronized (reportLock) {
                    byte[] report = (id == REPORT_ID_MOUSE) ? mouseReport : keyboardReport;
                    hidDevice.replyReport(device, type, id, report);
                }
            }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {}

        @Override
        public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) {}
    };

    // ===== 蓝牙状态广播 =====

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF) {
                    isConnected = false;
                    connectedDevice = null;
                    if (connectionCallback != null)
                        connectionCallback.onConnectionStateChanged(false, null);
                }
            }
        }
    };
}