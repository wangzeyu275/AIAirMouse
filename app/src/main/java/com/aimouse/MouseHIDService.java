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
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 蓝牙HID鼠标服务 — 手机模拟为标准蓝牙鼠标。
 *
 * 使用 Android 官方 BluetoothHidDevice API 实现：
 *   - HID 注册与广播接收器配置
 *   - 标准鼠标报告描述符（5按钮 + X/Y位移 + 垂直滚轮 + 水平滚轮）
 *   - 实时HID报告包发送（后台线程处理，不阻塞UI）
 *   - 前台服务保活机制（App退到后台时蓝牙连接不中断）
 */
public class MouseHIDService extends Service {

    private static final String TAG = "MouseHIDService";
    private static final String CHANNEL_ID = "mouse_hid_channel";
    private static final int NOTIFICATION_ID = 1;

    // ===== 标准鼠标HID报告描述符 =====
    // 报告格式（5字节）：
    //   Byte 0: 按钮位 [0:左键, 1:右键, 2:中键, 3:按钮4, 4:按钮5, 5-7:填充]
    //   Byte 1: X轴相对位移 (signed 8-bit, -127~127)
    //   Byte 2: Y轴相对位移 (signed 8-bit, -127~127)
    //   Byte 3: 垂直滚轮 (signed 8-bit, -127~127)
    //   Byte 4: 水平滚轮/AC Pan (signed 8-bit, -127~127)
    private static final byte[] HID_REPORT_DESCRIPTOR = {
            // Usage Page (Generic Desktop)
            0x05, 0x01,
            // Usage (Mouse)
            0x09, 0x02,
            // Collection (Application)
            (byte) 0xA1, 0x01,
            //   Usage (Pointer)
            0x09, 0x01,
            //   Collection (Physical)
            (byte) 0xA1, 0x00,
            //     Usage Page (Button)
            0x05, 0x09,
            //     Usage Minimum (1)
            0x19, 0x01,
            //     Usage Maximum (5)
            0x29, 0x05,
            //     Logical Minimum (0)
            0x15, 0x00,
            //     Logical Maximum (1)
            0x25, 0x01,
            //     Report Count (5)
            (byte) 0x95, 0x05,
            //     Report Size (1)
            0x75, 0x01,
            //     Input (Data, Variable, Absolute) — 5 button bits
            (byte) 0x81, 0x02,
            //     Report Count (1) — 3 bits padding
            (byte) 0x95, 0x01,
            //     Report Size (3)
            0x75, 0x03,
            //     Input (Constant, Variable, Absolute) — padding
            (byte) 0x81, 0x03,
            //     Usage Page (Generic Desktop)
            0x05, 0x01,
            //     Usage (X)
            0x09, 0x30,
            //     Usage (Y)
            0x09, 0x31,
            //     Usage (Wheel)
            0x09, 0x38,
            //     Logical Minimum (-127)
            0x15, (byte) 0x81,
            //     Logical Maximum (127)
            0x25, 0x7F,
            //     Report Size (8)
            0x75, 0x08,
            //     Report Count (3)
            (byte) 0x95, 0x03,
            //     Input (Data, Variable, Relative) — X, Y, Wheel
            (byte) 0x81, 0x06,
            //   End Collection
            (byte) 0xC0,
            //   Usage Page (Consumer)
            0x05, 0x0C,
            //   Usage (AC Pan) — horizontal wheel
            0x0A, 0x38, 0x02,
            //   Logical Minimum (-127)
            0x15, (byte) 0x81,
            //   Logical Maximum (127)
            0x25, 0x7F,
            //   Report Size (8)
            0x75, 0x08,
            //   Report Count (1)
            (byte) 0x95, 0x01,
            //   Input (Data, Variable, Relative)
            (byte) 0x81, 0x06,
            // End Collection
            (byte) 0xC0
    };

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

    // ===== 当前HID报告状态 =====
    private final byte[] currentReport = new byte[5]; // 5字节报告
    private final Object reportLock = new Object();

    // ===== 连接状态回调 =====
    private ConnectionCallback connectionCallback;

    // ===== Binder =====
    private final IBinder binder = new MouseHIDBinder();

    /**
     * 连接状态回调接口。
     */
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

        // 初始化蓝牙适配器
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "设备不支持蓝牙");
            if (connectionCallback != null) {
                connectionCallback.onError("设备不支持蓝牙");
            }
            return;
        }

        // 注册蓝牙状态广播
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(bluetoothStateReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 服务被杀后自动重启
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

    /**
     * 设置连接状态回调。
     */
    public void setConnectionCallback(ConnectionCallback callback) {
        this.connectionCallback = callback;
    }

    /**
     * 注册HID设备 — 使手机被电脑发现为蓝牙鼠标。
     */
    public boolean registerHID() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "蓝牙不可用");
            return false;
        }

        try {
            // 获取 BluetoothHidDevice 代理
            bluetoothAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    hidDevice = (BluetoothHidDevice) proxy;

                    // 注册HID设备App
                    BluetoothHidDeviceAppSdpSettings sdpSettings =
                            new BluetoothHidDeviceAppSdpSettings(
                                    "AI Air Mouse",           // 名称
                                    "AI空中鼠标",              // 描述
                                    "Android",                 // 提供商
                                    BluetoothHidDevice.SUBCLASS1_COMBO, // 子类
                                    HID_REPORT_DESCRIPTOR       // 报告描述符
                            );

                    BluetoothHidDeviceAppQosSettings qosSettings =
                            new BluetoothHidDeviceAppQosSettings(
                                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                                    800,    // 令牌速率
                                    0,      // 令牌桶大小
                                    0,      // 峰值带宽
                                    BluetoothHidDeviceAppQosSettings.MAX
                            );

                    boolean result = hidDevice.registerApp(
                            sdpSettings, null, qosSettings,
                            Runnable::run,  // Executor
                            hidDeviceCallback
                    );

                    if (result) {
                        isRegistered = true;
                        Log.i(TAG, "HID设备注册成功，等待电脑连接…");
                        updateNotification("等待蓝牙配对…");
                    } else {
                        Log.e(TAG, "HID设备注册失败");
                        if (connectionCallback != null) {
                            connectionCallback.onError("HID设备注册失败");
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    hidDevice = null;
                    isRegistered = false;
                    Log.w(TAG, "BluetoothHidDevice服务断开");
                }
            }, BluetoothProfile.HID_DEVICE);

            return true;
        } catch (SecurityException e) {
            // BLUETOOTH_PRIVILEGED 权限未授予
            Log.e(TAG, "缺少BLUETOOTH_PRIVILEGED权限: " + e.getMessage());
            if (connectionCallback != null) {
                connectionCallback.onError(
                        "缺少BLUETOOTH_PRIVILEGED权限\n请通过ADB执行:\n" +
                                "adb shell pm grant com.aimouse android.permission.BLUETOOTH_PRIVILEGED"
                );
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "HID注册异常: " + e.getMessage(), e);
            if (connectionCallback != null) {
                connectionCallback.onError("HID注册异常: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * 注销HID设备。
     */
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
                Log.i(TAG, "HID设备已注销");
            }
        } catch (Exception e) {
            Log.e(TAG, "注销HID设备异常: " + e.getMessage(), e);
        }
    }

    /**
     * 断开当前HID连接。
     */
    public void disconnect() {
        try {
            if (hidDevice != null && isConnected && connectedDevice != null) {
                hidDevice.disconnect(connectedDevice);
            }
        } catch (Exception e) {
            Log.e(TAG, "断开连接异常: " + e.getMessage(), e);
        }
    }

    /**
     * 获取连接状态。
     */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * 获取已注册状态。
     */
    public boolean isRegistered() {
        return isRegistered;
    }

    // ===== 鼠标操作接口 =====

    /**
     * 移动光标。
     * @param dx X轴相对位移
     * @param dy Y轴相对位移
     */
    public void moveCursor(int dx, int dy) {
        synchronized (reportLock) {
            // 限制在signed byte范围
            currentReport[1] = (byte) clampToByte(dx);
            currentReport[2] = (byte) clampToByte(dy);
        }
        sendReport();
    }

    /**
     * 左键单击（按下后立即释放）。
     */
    public void clickLeft() {
        // 按下
        setButton(0, true);
        sendReport();
        // 延迟释放
        backgroundHandler.postDelayed(() -> {
            setButton(0, false);
            sendReport();
        }, 50);
    }

    /**
     * 左键按下。
     */
    public void leftButtonDown() {
        setButton(0, true);
        sendReport();
    }

    /**
     * 左键释放。
     */
    public void leftButtonUp() {
        setButton(0, false);
        sendReport();
    }

    /**
     * 右键单击。
     */
    public void clickRight() {
        setButton(1, true);
        sendReport();
        backgroundHandler.postDelayed(() -> {
            setButton(1, false);
            sendReport();
        }, 50);
    }

    /**
     * 中键单击。
     */
    public void clickMiddle() {
        setButton(2, true);
        sendReport();
        backgroundHandler.postDelayed(() -> {
            setButton(2, false);
            sendReport();
        }, 50);
    }

    /**
     * 垂直滚轮滚动。
     * @param amount 正数向上，负数向下
     */
    public void scrollVertical(int amount) {
        synchronized (reportLock) {
            currentReport[3] = (byte) clampToByte(amount);
        }
        sendReport();
        // 滚轮报告需要归零
        backgroundHandler.postDelayed(() -> {
            synchronized (reportLock) {
                currentReport[3] = 0;
            }
            sendReport();
        }, 30);
    }

    /**
     * 水平滚轮滚动。
     * @param amount 滚动量
     */
    public void scrollHorizontal(int amount) {
        synchronized (reportLock) {
            currentReport[4] = (byte) clampToByte(amount);
        }
        sendReport();
        backgroundHandler.postDelayed(() -> {
            synchronized (reportLock) {
                currentReport[4] = 0;
            }
            sendReport();
        }, 30);
    }

    /**
     * 重置所有报告数据。
     */
    public void resetReport() {
        synchronized (reportLock) {
            for (int i = 0; i < currentReport.length; i++) {
                currentReport[i] = 0;
            }
        }
        sendReport();
    }

    // ===== 私有方法 =====

    /**
     * 设置按钮位。
     * @param buttonIndex 0=左键, 1=右键, 2=中键, 3=按钮4, 4=按钮5
     */
    private void setButton(int buttonIndex, boolean pressed) {
        synchronized (reportLock) {
            if (pressed) {
                currentReport[0] |= (byte) (1 << buttonIndex);
            } else {
                currentReport[0] &= (byte) ~(1 << buttonIndex);
            }
        }
    }

    /**
     * 发送HID报告到电脑。
     */
    private void sendReport() {
        if (!isConnected || hidDevice == null || connectedDevice == null) {
            return;
        }

        reportExecutor.execute(() -> {
            try {
                byte[] report;
                synchronized (reportLock) {
                    report = currentReport.clone();
                }
                hidDevice.sendReport(connectedDevice, 0, report);
            } catch (Exception e) {
                Log.e(TAG, "发送HID报告失败: " + e.getMessage());
            }
        });
    }

    /**
     * 将int值限制在signed byte范围内。
     */
    private static int clampToByte(int value) {
        return Math.max(-127, Math.min(127, value));
    }

    /**
     * 创建通知渠道。
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notification_channel_desc));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 构建前台服务通知。
     */
    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

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

    /**
     * 更新通知内容。
     */
    private void updateNotification(String text) {
        Notification notification = buildNotification(text);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    // ===== BluetoothHidDevice 回调 =====
    private final BluetoothHidDevice.Callback hidDeviceCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice device, boolean registered) {
            Log.i(TAG, "HID App状态变更: device=" + device + ", registered=" + registered);
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            Log.i(TAG, "HID连接状态变更: device=" + device + ", state=" + state);

            if (state == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                connectedDevice = device;
                updateNotification("已连接到 " + device.getName());
                if (connectionCallback != null) {
                    connectionCallback.onConnectionStateChanged(true, device);
                }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                connectedDevice = null;
                updateNotification("连接已断开，等待重新连接…");
                if (connectionCallback != null) {
                    connectionCallback.onConnectionStateChanged(false, device);
                }
            }
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            Log.i(TAG, "onGetReport: device=" + device + ", type=" + type + ", id=" + id);
            // 电脑请求报告，回传当前报告
            if (hidDevice != null) {
                synchronized (reportLock) {
                    hidDevice.replyReport(device, type, id, currentReport);
                }
            }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            Log.i(TAG, "onSetReport: device=" + device);
        }

        @Override
        public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) {
            Log.i(TAG, "onInterruptData: device=" + device);
        }
    };

    // ===== 蓝牙状态广播接收器 =====
    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF) {
                    // 蓝牙关闭，清理状态
                    isConnected = false;
                    connectedDevice = null;
                    if (connectionCallback != null) {
                        connectionCallback.onConnectionStateChanged(false, null);
                    }
                }
            }
        }
    };
}