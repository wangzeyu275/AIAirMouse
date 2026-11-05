package com.aimouse;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 权限辅助类 — 通过 Shizuku 在手机端直接授予 BLUETOOTH_PRIVILEGED 权限。
 *
 * 工作原理：
 *   1. 检测 Shizuku 是否已安装且正在运行
 *   2. 请求 Shizuku 授权（用户需在弹窗中同意）
 *   3. 通过 Shizuku 执行 pm grant 命令，等价于：
 *      adb shell pm grant com.aimouse android.permission.BLUETOOTH_PRIVILEGED
 *   4. 验证授权是否成功
 *
 * 优势：
 *   - 无需连接电脑
 *   - 无需 Root
 *   - 一次授权，永久生效（除非卸载 App）
 */
public class ShizukuHelper {

    private static final String TAG = "ShizukuHelper";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String PERMISSION_TO_GRANT = "android.permission.BLUETOOTH_PRIVILEGED";
    private static final String TARGET_PACKAGE = "com.aimouse";

    /**
     * 授权结果回调。
     */
    public interface GrantCallback {
        void onSuccess();
        void onFailure(String reason);
        void onShizukuNotAvailable();
    }

    /**
     * 检查 Shizuku 是否已安装。
     */
    public static boolean isShizukuInstalled(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    SHIZUKU_PACKAGE, 0);
            return info != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 检查 Shizuku 是否正在运行且可用。
     */
    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Exception e) {
            Log.e(TAG, "Shizuku pingBinder 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查 Shizuku 权限是否已授予本 App。
     */
    public static boolean hasShizukuPermission() {
        try {
            if (!isShizukuAvailable()) return false;
            int result = Shizuku.checkSelfPermission();
            return result == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.e(TAG, "检查 Shizuku 权限失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查 BLUETOOTH_PRIVILEGED 权限是否已授予。
     */
    public static boolean isPrivilegeGranted(Context context) {
        try {
            int result = context.getPackageManager().checkPermission(
                    PERMISSION_TO_GRANT, TARGET_PACKAGE);
            return result == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.e(TAG, "检查权限状态失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 通过 Shizuku 执行 pm grant 命令授予 BLUETOOTH_PRIVILEGED 权限。
     *
     * @param callback 授权结果回调
     */
    public static void grantPrivilegePermission(GrantCallback callback) {
        if (!isShizukuAvailable()) {
            callback.onShizukuNotAvailable();
            return;
        }

        if (!hasShizukuPermission()) {
            callback.onFailure("Shizuku 权限未授予，请先在弹窗中允许本 App 使用 Shizuku");
            return;
        }

        // 通过 Shizuku 执行 shell 命令
        String command = "pm grant " + TARGET_PACKAGE + " " + PERMISSION_TO_GRANT;

        new Thread(() -> {
            try {
                // Shizuku.newProcess 在 13.x 中为私有方法，需通过反射调用
                Method newProcess = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                newProcess.setAccessible(true);
                Process process = (Process) newProcess.invoke(null,
                        new String[]{"/system/bin/sh", "-c", command},
                        null, null);

                // 读取输出
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                BufferedReader errorReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()));

                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                while ((line = errorReader.readLine()) != null) {
                    output.append(line).append("\n");
                }

                int exitCode = process.waitFor();
                process.destroy();

                Log.i(TAG, "pm grant 执行结果: exitCode=" + exitCode + ", output=" + output);

                if (exitCode == 0) {
                    callback.onSuccess();
                } else {
                    callback.onFailure("命令执行失败 (exit=" + exitCode + "): " + output);
                }
            } catch (Exception e) {
                Log.e(TAG, "Shizuku 执行命令异常: " + e.getMessage(), e);
                callback.onFailure("执行异常: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 完整的 Shizuku 授权流程。
     * 适用于 App 首次启动时检测到权限缺失，引导用户通过 Shizuku 授权。
     *
     * @param activity 当前 Activity
     * @param onComplete 流程完成后的回调（无论成功失败）
     */
    public static void startGrantFlow(Activity activity, Runnable onComplete) {
        // 第一步：检查是否已安装 Shizuku
        if (!isShizukuInstalled(activity)) {
            showInstallShizukuDialog(activity, onComplete);
            return;
        }

        // 第二步：检查 Shizuku 是否运行
        if (!isShizukuAvailable()) {
            showShizukuNotRunningDialog(activity, onComplete);
            return;
        }

        // 第三步：检查 Shizuku 权限
        if (!hasShizukuPermission()) {
            requestShizukuPermission(activity, onComplete);
            return;
        }

        // 第四步：执行授权
        grantPrivilegePermission(new GrantCallback() {
            @Override
            public void onSuccess() {
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity,
                            "BLUETOOTH_PRIVILEGED 权限授权成功！",
                            Toast.LENGTH_LONG).show();
                    if (onComplete != null) onComplete.run();
                });
            }

            @Override
            public void onFailure(String reason) {
                activity.runOnUiThread(() -> {
                    new AlertDialog.Builder(activity)
                            .setTitle("授权失败")
                            .setMessage("Shizuku 授权失败：" + reason
                                    + "\n\n备选方案：通过 USB 连接电脑，执行：\n"
                                    + "adb shell pm grant com.aimouse "
                                    + "android.permission.BLUETOOTH_PRIVILEGED")
                            .setPositiveButton("确定", (d, w) -> {
                                if (onComplete != null) onComplete.run();
                            })
                            .show();
                });
            }

            @Override
            public void onShizukuNotAvailable() {
                activity.runOnUiThread(() -> {
                    showShizukuNotRunningDialog(activity, onComplete);
                });
            }
        });
    }

    /**
     * 请求 Shizuku 运行时权限。
     */
    private static void requestShizukuPermission(Activity activity, Runnable onComplete) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Shizuku API 13+ 使用新的权限请求方式
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0);
                // 监听权限请求结果
                Shizuku.addRequestPermissionResultListener(
                        (requestCode, grantResult) -> {
                            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                // 权限已授予，继续执行 pm grant
                                grantPrivilegePermission(new GrantCallback() {
                                    @Override
                                    public void onSuccess() {
                                        activity.runOnUiThread(() -> {
                                            Toast.makeText(activity,
                                                    "权限授权成功！", Toast.LENGTH_LONG).show();
                                            if (onComplete != null) onComplete.run();
                                        });
                                    }

                                    @Override
                                    public void onFailure(String reason) {
                                        activity.runOnUiThread(() -> {
                                            Toast.makeText(activity,
                                                    "授权失败: " + reason, Toast.LENGTH_LONG).show();
                                            if (onComplete != null) onComplete.run();
                                        });
                                    }

                                    @Override
                                    public void onShizukuNotAvailable() {
                                        activity.runOnUiThread(() -> {
                                            showShizukuNotRunningDialog(activity, onComplete);
                                        });
                                    }
                                });
                            } else {
                                activity.runOnUiThread(() -> {
                                    Toast.makeText(activity,
                                            "Shizuku 权限被拒绝", Toast.LENGTH_SHORT).show();
                                    if (onComplete != null) onComplete.run();
                                });
                            }
                        });
            }
        }
    }

    /**
     * 显示"请安装 Shizuku"对话框。
     */
    private static void showInstallShizukuDialog(Activity activity, Runnable onDismiss) {
        new AlertDialog.Builder(activity)
                .setTitle("需要 Shizuku")
                .setMessage("Shizuku 可帮助您在手机上直接获取所需权限，无需连接电脑。\n\n"
                        + "请先安装 Shizuku App：\n"
                        + "1. Google Play 搜索 \"Shizuku\"\n"
                        + "2. 或访问 https://shizuku.rikka.app\n\n"
                        + "安装后启动 Shizuku 并通过无线调试启动服务。")
                .setPositiveButton("去下载", (d, w) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://shizuku.rikka.app/download/"));
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(activity,
                                "请手动搜索 Shizuku 下载", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("暂不需要", (d, w) -> {
                    if (onDismiss != null) onDismiss.run();
                })
                .setNeutralButton("手动 ADB 授权", (d, w) -> {
                    showManualAdbDialog(activity, onDismiss);
                })
                .show();
    }

    /**
     * 显示"Shizuku 未运行"对话框。
     */
    private static void showShizukuNotRunningDialog(Activity activity, Runnable onDismiss) {
        new AlertDialog.Builder(activity)
                .setTitle("Shizuku 未运行")
                .setMessage("请先打开 Shizuku App 并启动服务：\n\n"
                        + "Android 11+ 设备：\n"
                        + "  使用「无线调试」方式启动\n\n"
                        + "Android 10 及以下：\n"
                        + "  需要通过 USB 连接电脑后启动")
                .setPositiveButton("打开 Shizuku", (d, w) -> {
                    try {
                        Intent intent = activity.getPackageManager()
                                .getLaunchIntentForPackage(SHIZUKU_PACKAGE);
                        if (intent != null) {
                            activity.startActivity(intent);
                        }
                    } catch (Exception e) {
                        Toast.makeText(activity,
                                "无法打开 Shizuku", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("暂不需要", (d, w) -> {
                    if (onDismiss != null) onDismiss.run();
                })
                .setNeutralButton("手动 ADB 授权", (d, w) -> {
                    showManualAdbDialog(activity, onDismiss);
                })
                .show();
    }

    /**
     * 显示手动 ADB 授权说明对话框。
     */
    private static void showManualAdbDialog(Activity activity, Runnable onDismiss) {
        new AlertDialog.Builder(activity)
                .setTitle("手动 ADB 授权")
                .setMessage("请通过 USB 数据线将手机连接电脑，然后执行以下命令：\n\n"
                        + "adb shell pm grant com.aimouse \\\n"
                        + "  android.permission.BLUETOOTH_PRIVILEGED\n\n"
                        + "执行后无需再次操作，权限将永久生效。")
                .setPositiveButton("已执行完成", (d, w) -> {
                    // 验证权限是否已授予
                    if (isPrivilegeGranted(activity)) {
                        Toast.makeText(activity,
                                "权限验证通过！", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(activity,
                                "权限仍未授予，请检查命令是否正确执行",
                                Toast.LENGTH_LONG).show();
                    }
                    if (onDismiss != null) onDismiss.run();
                })
                .setNegativeButton("稍后操作", (d, w) -> {
                    if (onDismiss != null) onDismiss.run();
                })
                .show();
    }
}