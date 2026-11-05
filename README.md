# AI空中鼠标 (AI Air Mouse)

一款利用手机内置陀螺仪和加速度计控制电脑鼠标指针的 Android App。支持蓝牙 HID 直连和 USB 有线直连两种物理连接方式，集成虚拟触控板、手势识别、卡尔曼滤波和自适应灵敏度等 AI 智能增强功能。

---

## 目录

1. [核心特性](#核心特性)
2. [项目结构](#项目结构)
3. [编译与安装](#编译与安装)
4. [权限说明（Shizuku 可选）](#权限说明shizuku-可选)
5. [蓝牙配对操作指南](#蓝牙配对操作指南)
6. [触控板操作说明](#触控板操作说明)
7. [USB ADB 方案](#usb-adb-方案)
8. [USB HID Gadget 方案（Root）](#usb-hid-gadget-方案root)
9. [技术架构说明](#技术架构说明)
10. [常见问题排查](#常见问题排查)

---

## 核心特性

| 功能 | 说明 |
|------|------|
| 蓝牙 HID 直连 | 手机模拟标准蓝牙鼠标，电脑端零依赖 |
| 虚拟触控板 | 屏幕中央半透明触控板，支持单指/双指操作 |
| 指针移动开关 | 可独立控制触控板是否移动指针，防止误触 |
| 卡尔曼滤波 | 融合陀螺仪+加速度计数据，消除手持抖动 |
| 自适应灵敏度 | 根据移动速度实时调整光标灵敏度增益 |
| 手势识别 | 甩动、画圈等手势映射为快捷键操作 |
| 前台服务保活 | App 退到后台时蓝牙连接不中断 |

---

## 项目结构

```
AIAirMouse/
├── build.gradle                    # 项目级构建配置
├── settings.gradle                 # 项目设置
├── gradle.properties               # Gradle 属性
├── gradlew / gradlew.bat           # Gradle Wrapper 启动脚本
├── gradle/wrapper/
│   ├── gradle-wrapper.jar          # Wrapper JAR
│   └── gradle-wrapper.properties    # Gradle Wrapper 配置
├── app/
│   ├── build.gradle                # 模块级构建配置
│   ├── proguard-rules.pro          # 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml     # 应用清单（权限、组件声明）
│       ├── java/com/aimouse/
│       │   ├── MainActivity.java           # 主界面
│       │   ├── MouseHIDService.java        # 蓝牙HID鼠标服务
│       │   ├── TouchpadView.java           # 虚拟触控板自定义View
│       │   ├── KalmanFilter.java           # 卡尔曼滤波器
│       │   ├── GestureRecognitionEngine.java # 手势识别引擎
│       │   ├── AdaptiveSensitivity.java    # 自适应灵敏度
│       │   └── ShizukuHelper.java           # Shizuku 权限辅助（可选高级选项）
│       └── res/
│           ├── layout/activity_main.xml    # 主界面布局
│           ├── values/strings.xml          # 字符串资源
│           ├── values/colors.xml           # 颜色资源
│           ├── values/themes.xml           # 主题资源
│           ├── drawable/                   # 图标和状态指示器
│           └── mipmap-anydpi-v26/          # 启动图标
├── scripts/
│   ├── usb_adb_mouse.bat           # USB ADB 方案 Windows 脚本
│   ├── usb_adb_mouse.sh            # USB ADB 方案 macOS/Linux 脚本
│   └── usb_hid_gadget.c            # USB HID Gadget 方案 C 代码
├── LICENSE                         # Apache License 2.0
├── NOTICE                          # 第三方依赖许可声明
└── README.md                       # 本文档
```

---

## 编译与安装

### 环境要求

- **Android Studio** Hedgehog (2023.1.1) 或更高版本
- **JDK** 17 或更高
- **Android SDK** Platform 34
- **Gradle** 8.5（项目自带 Wrapper，自动下载）

### 编译步骤

1. **克隆或下载项目到本地**

2. **用 Android Studio 打开项目**
   - 启动 Android Studio
   - 选择 `File → Open`
   - 选择 `AIAirMouse` 目录

3. **等待 Gradle 同步完成**
   - Android Studio 会自动下载 Gradle Wrapper 和依赖
   - 首次同步可能需要几分钟

4. **编译 APK**
   - 选择 `Build → Build Bundle(s) / APK(s) → Build APK(s)`
   - 或使用命令行：`./gradlew assembleDebug`
   - APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### 安装到手机

**方法一：通过 Android Studio 直接安装**
- 手机通过 USB 连接电脑，开启 USB 调试
- 点击 `Run → Run 'app'` 或按 `Shift+F10`

**方法二：通过 ADB 手动安装**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

**方法三：将 APK 复制到手机直接安装**
- 将 APK 文件传输到手机
- 在手机上打开文件管理器，点击 APK 安装
- 需要允许"未知来源"安装

---

## 权限说明（Shizuku 可选）

> **常规使用不需要 `BLUETOOTH_PRIVILEGED`。** App 注册蓝牙 HID 只依赖运行时蓝牙权限：
> - Android 12+：`BLUETOOTH_CONNECT`、`BLUETOOTH_SCAN`、`BLUETOOTH_ADVERTISE`
> - Android 9–11：`BLUETOOTH`、`BLUETOOTH_ADMIN`

首次启动时按系统弹窗允许“附近的设备”权限即可。`BLUETOOTH_PRIVILEGED` 属于 `signature` 级权限，普通安装通常无法获得，因此**不再作为首次启动的强制步骤，也不会在启动时弹窗索取**。

### 可选高级选项：Shizuku

个别 ROM 在 HID 注册时报权限错误时，可尝试 App 底部的 **高级选项（Shizuku，可选）**：

1. 从 [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) 或 [官网](https://shizuku.rikka.app/download/) 安装 Shizuku
2. 按 Shizuku 引导启动服务（Android 11+ 可通过无线调试启动）
3. 打开 App → 高级选项，查看 Shizuku 安装/运行/授权及 `BLUETOOTH_PRIVILEGED` 状态
4. 点击“开始 Shizuku 授权”；未安装或未运行时会给出对应引导

> 说明：`pm grant ... BLUETOOTH_PRIVILEGED` 在多数设备上会被系统拒绝（签名级权限），该入口仅用于排查，失败不影响常规蓝牙 HID 使用。

### 备选：手动 ADB（可选）

```bash
adb shell pm grant com.aimouse android.permission.BLUETOOTH_PRIVILEGED
```

**验证：**
```bash
adb shell dumpsys package com.aimouse | grep BLUETOOTH_PRIVILEGED
```

---

## 蓝牙配对操作指南

### Windows 11

1. 打开 **设置 → 蓝牙和其他设备**
2. 点击 **添加设备** → 选择 **蓝牙**
3. 在手机上：
   - 打开 AI空中鼠标 App
   - 确保蓝牙已开启
   - 点击 **注册HID设备** 按钮
   - App 会显示"等待电脑连接…"
4. 在电脑上找到名为 **"AI Air Mouse"** 的设备
5. 点击连接/配对
6. 连接成功后，App 状态栏显示"已连接: 你的电脑名称"
7. 此时手机已作为蓝牙鼠标工作，可以直接移动手机控制指针

**注意：** Windows 11 首次配对可能需要 10-30 秒，请耐心等待。

### macOS

1. 打开 **系统设置 → 蓝牙**
2. 确保蓝牙已开启
3. 在手机上打开 AI空中鼠标 App，点击 **注册HID设备**
4. 在 Mac 蓝牙设备列表中找到 **"AI Air Mouse"**
5. 点击 **连接**
6. 连接成功后即可使用

**注意：** macOS 对 HID 设备的兼容性较好，通常配对速度比 Windows 快。

### Linux (Ubuntu/Debian)

1. 打开 **设置 → 蓝牙**
2. 确保蓝牙已开启
3. 在手机上打开 AI空中鼠标 App，点击 **注册HID设备**
4. 在 Linux 蓝牙设备列表中找到 **"AI Air Mouse"**
5. 点击连接

**命令行方式：**
```bash
# 扫描蓝牙设备
bluetoothctl scan on

# 找到 AI Air Mouse 的 MAC 地址后
bluetoothctl pair XX:XX:XX:XX:XX:XX
bluetoothctl connect XX:XX:XX:XX:XX:XX
bluetoothctl trust XX:XX:XX:XX:XX:XX
```

---

## 触控板操作说明

### 指针移动开关

App 主界面有一个醒目的 **指针移动** 开关（Switch 控件），控制虚拟触控板是否可以移动电脑指针。

- **开关状态通过 SharedPreferences 持久化**，下次打开 App 自动恢复上次设置
- 开关状态实时显示在触控板区域上方

### 指针移动开关：开启（默认）

| 操作 | 效果 |
|------|------|
| 单指滑动 | 移动电脑鼠标指针 |
| 单指轻点 | 鼠标左键单击 |
| 单指长按 (>500ms) | 鼠标左键按住（用于拖拽文件/选择文本） |
| 双指上下滑动 | 鼠标滚轮垂直滚动 |
| 双指左右滑动 | 鼠标滚轮水平滚动 |

### 指针移动开关：关闭

| 操作 | 效果 |
|------|------|
| 单指滑动 | **指针保持静止**，滑动轨迹被忽略 |
| 单指轻点 | 鼠标左键单击（正常生效） |
| 单指长按 (>500ms) | 鼠标左键按住（正常生效） |
| 双指上下滑动 | 鼠标滚轮垂直滚动（正常生效） |
| 双指左右滑动 | 鼠标滚轮水平滚动（正常生效） |

**关闭模式的核心价值：** 防止误触导致指针乱飞，同时保留点击和滚轮浏览功能，适合阅读文档和网页浏览场景。

### 防误触机制

- 触控板自动过滤手掌或大面积误触
- 单指触摸面积超过正常指尖 1.5 倍时自动忽略
- 触摸事件采样率独立运行于 60fps 以上

---

## USB ADB 方案

### 方案说明

基于 ADB 指令模拟鼠标，利用 `adb shell input mouse` 命令控制电脑指针。

| 特性 | 说明 |
|------|------|
| Root 要求 | 不需要 |
| 电脑端依赖 | 仅需安装 ADB 驱动 |
| 延迟 | ~15-30ms |
| 适用平台 | Windows 10+, macOS, Linux |

### 使用方法

**Windows:**
```cmd
scripts\usb_adb_mouse.bat
```

**macOS/Linux:**
```bash
chmod +x scripts/usb_adb_mouse.sh
./scripts/usb_adb_mouse.sh
```

### 前置条件

1. 电脑安装 ADB：
   - Windows: 下载 [Platform Tools](https://developer.android.com/studio/releases/platform-tools)
   - macOS: `brew install android-platform-tools`
   - Linux: `sudo apt install adb`
2. 手机开启 USB 调试
3. 手机通过 USB 数据线连接电脑
4. 手机授权电脑的调试请求

---

## USB HID Gadget 方案（Root）

### 方案说明

针对已 Root 的 Android 设备，利用内核级 USB HID Gadget 模式，让手机在硬件层面被电脑识别为有线鼠标。

| 特性 | 说明 |
|------|------|
| Root 要求 | **需要 Root** |
| 电脑端依赖 | **零依赖** |
| 延迟 | **~1-5ms（极低）** |
| 适用平台 | Windows, macOS, Linux |

### 快速配置

```bash
# 1. 加载内核模块
insmod /system/lib/modules/usb_f_hid.ko

# 2. 配置 USB Gadget (ConfigFS)
cd /sys/kernel/config/usb_gadget
mkdir g1 && cd g1

# 3. 设置设备信息
echo 0x046d > idVendor
echo 0xc077 > idProduct
echo 0x0200 > bcdUSB

# 4. 配置 HID 功能
mkdir functions/hid.usb0
echo 0 > functions/hid.usb0/protocol
echo 4 > functions/hid.usb0/report_length

# 5. 写入报告描述符 (省略二进制，见代码注释)
# 6. 绑定并启用
mkdir configs/c.1
ln -s functions/hid.usb0 configs/c.1/
echo $(ls /sys/class/udc) > UDC

# 7. 发送鼠标数据
echo -ne '\x00\x0a\x00\x00' > /dev/hidg0
```

### C 语言控制程序

详见 `scripts/usb_hid_gadget.c`，编译后可通过命令行精确控制鼠标。

### 三种方案延迟对比

| 方案 | 延迟 | 电脑端依赖 | Root 要求 |
|------|------|-----------|----------|
| 蓝牙 HID | ~8-15ms | 零依赖 | 不需要 |
| USB ADB | ~15-30ms | ADB 驱动 | 不需要 |
| USB HID Gadget | **~1-5ms** | **零依赖** | **需要** |

---

## 技术架构说明

### 传感器融合（卡尔曼滤波）

[KalmanFilter.java](app/src/main/java/com/aimouse/KalmanFilter.java) 实现了标准一维卡尔曼滤波器：

**预测方程：**
```
x̂_k|k-1 = x̂_{k-1|k-1}
P_k|k-1  = P_{k-1|k-1} + Q
```

**更新方程：**
```
K_k    = P_k|k-1 / (P_k|k-1 + R)
x̂_k|k = x̂_k|k-1 + K_k * (z_k - x̂_k|k-1)
P_k|k  = (1 - K_k) * P_k|k-1
```

**推荐参数：**
- 50Hz 采样率：Q = 0.01, R = 0.1
- 200Hz 采样率：Q = 0.01, R = 0.05（降低测量噪声权重以增加响应灵敏度）

### 自适应灵敏度

[AdaptiveSensitivity.java](app/src/main/java/com/aimouse/AdaptiveSensitivity.java) 采用分段增益公式：

```
gain = 1.2 / (1 + e^(-speed / 100))
```

- speed < 50 px/s  → gain ≈ 0.6（精细定位）
- speed > 200 px/s → gain ≈ 1.8（大幅跨屏）

### 手势识别

[GestureRecognitionEngine.java](app/src/main/java/com/aimouse/GestureRecognitionEngine.java) 支持四种手势：

| 手势 | 触发条件 | 映射操作 |
|------|---------|---------|
| 水平甩动 | \|ax\| > 14 m/s², 窗口 ≤ 150ms | Alt+Tab 窗口切换 |
| 垂直甩动 | \|ay\| > 14 m/s², 窗口 ≤ 150ms | 音量加减 |
| 顺时针画圈 | 叉积一致性 > 0.6 | 刷新(F5) / 静音 |
| 逆时针画圈 | 叉积一致性 > 0.6 | 撤销(Ctrl+Z) / 恢复 |

### HID 报告描述符

[MouseHIDService.java](app/src/main/java/com/aimouse/MouseHIDService.java) 通过一个 HID 设备注册三个报告：

| Report ID | 类型 | 载荷长度 | 内容 |
|-----------|------|----------|------|
| 0x01 | 鼠标 | 5 字节 | 按钮位、X、Y、垂直滚轮、水平滚轮 |
| 0x02 | 键盘 | 8 字节 | 修饰键、保留位、6 个键位 |
| 0x03 | Consumer | 2 字节 | 16-bit usage（音量/媒体键等） |

鼠标报告载荷：

| 字节 | 内容 |
|------|------|
| Byte 0 | 按钮位：bit0=左键, bit1=右键, bit2=中键, bit3=按钮4, bit4=按钮5 |
| Byte 1 | X 轴相对位移 (-127 ~ 127) |
| Byte 2 | Y 轴相对位移 (-127 ~ 127) |
| Byte 3 | 垂直滚轮 (-127 ~ 127) |
| Byte 4 | 水平滚轮/AC Pan (-127 ~ 127) |

> 发送时调用 `sendReport(device, reportId, payload)`：`reportId` 单独传入，`payload` **不包含** Report ID 字节。

---

## 常见问题排查

### Q1: 蓝牙 HID 注册失败

**常见原因：**
- 未授予蓝牙权限（Android 12+ 需要“附近的设备”权限）
- 设备 ROM 不支持 Bluetooth HID Device Profile
- 系统安全策略拦截了 HID 注册

**处理顺序：**
1. 确认已允许蓝牙权限后，重新点击“注册HID设备”
2. 关闭再开启手机蓝牙，或重启手机
3. 确认设备支持蓝牙 HID Device（部分定制 ROM 会阉割该 Profile）
4. 仍然失败时，再尝试底部“高级选项（Shizuku，可选）”，该步骤不是必需

### Q2: 电脑搜索不到 "AI Air Mouse" 蓝牙设备

1. 确保手机蓝牙已开启
2. 确认 App 已点击"注册HID设备"按钮
3. 尝试关闭再开启电脑蓝牙
4. 重启手机蓝牙
5. 检查 Android 版本是否 ≥ 9.0（最低 SDK 28）

### Q3: 蓝牙已配对但鼠标指针不移动

1. 检查触控板上的 **指针移动开关** 是否已开启
2. 确认手机陀螺仪是否正常工作（查看传感器数据预览）
3. 尝试断开并重新配对蓝牙
4. 检查电脑蓝牙驱动是否最新

### Q4: 陀螺仪数据显示为 0 或不可用

- 部分手机没有陀螺仪硬件，App 会自动降级为**加速度计模式**
- 加速度计模式下精度较低，但基本可用
- 触控板功能不受影响

### Q5: 手机锁屏后蓝牙断开

- App 已实现前台服务保活机制
- 确保不要在系统设置中限制 App 后台运行
- 部分手机需要在"电池优化"中将 App 设为"不优化"

### Q6: 触控板不响应或误触频繁

- 触控板已内置防误触机制（过滤大面积触摸）
- 如果灵敏度不合适，可调整主界面的灵敏度滑块
- 双指操作时请确保两根手指同时触摸

### Q7: USB ADB 方案提示 "device not found"

```bash
# 检查 ADB 连接
adb devices

# 如果显示 unauthorized，在手机上点击"允许USB调试"
# 如果显示 offline，重启 ADB 服务
adb kill-server
adb start-server

# 重新检查
adb devices
```

### Q8: 如何卸载 App

```bash
adb uninstall com.aimouse
```

如果之前手动授予过 `BLUETOOTH_PRIVILEGED`，可在卸载前撤销（可选）：
```bash
adb shell pm revoke com.aimouse android.permission.BLUETOOTH_PRIVILEGED
```

---

## 开发说明

- **最低 SDK 版本：** 28 (Android 9.0)
- **目标 SDK 版本：** 34 (Android 14)
- **编译 SDK 版本：** 34
- **第三方依赖：** AndroidX AppCompat/Core；Shizuku API 仅用于可选高级选项
- **语言：** Java 8

---

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源，可自由使用、修改、商用和分发（需保留版权与许可声明）。欢迎提交 Issue 和 Pull Request。

Copyright (c) 2026 Zeyu Wang

本项目中使用的第三方库及其许可证详见 [NOTICE](NOTICE) 文件。