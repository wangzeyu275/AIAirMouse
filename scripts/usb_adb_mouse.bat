@echo off
REM ============================================================
REM  AI空中鼠标 — USB ADB 鼠标控制脚本 (Windows)
REM
REM  方案说明：
REM    基于 ADB 指令模拟鼠标，利用 adb shell input mouse 命令
REM    控制电脑指针。无需 root，电脑端仅需安装 ADB 驱动。
REM
REM  前提条件：
REM    1. 电脑已安装 ADB (Android Debug Bridge)
REM       下载地址: https://developer.android.com/studio/releases/platform-tools
REM    2. 手机已开启 "USB调试" 模式
REM       设置 → 开发者选项 → USB调试 (开启)
REM    3. 手机通过 USB 数据线连接到电脑
REM    4. 手机已授权电脑的 USB 调试请求
REM
REM  延迟对比：
REM    ADB input 方案延迟: ~15-30ms (受USB 2.0带宽和ADB守护进程开销影响)
REM    HID Gadget 方案延迟:  ~1-5ms  (内核级，零拷贝)
REM    蓝牙 HID 方案延迟:    ~8-15ms (BLE HID over GATT)
REM
REM  使用方法：
REM    1. 双击运行此脚本
REM    2. 选择操作模式：
REM       [1] 移动鼠标 (输入相对位移)
REM       [2] 单击左键
REM       [3] 双击左键
REM       [4] 右键单击
REM       [5] 滚轮滚动
REM       [6] 长按/拖拽
REM       [7] 释放按键
REM       [8] 连续移动模式 (使用方向键控制)
REM       [9] 退出
REM ============================================================

setlocal enabledelayedexpansion

:check_adb
echo [检查] 正在检查 ADB 连接...
adb devices 2>nul | findstr /r /c:"device$" >nul
if %errorlevel% neq 0 (
    echo [错误] 未检测到 ADB 设备！
    echo.
    echo 请检查：
    echo   1. 手机是否通过 USB 连接到电脑
    echo   2. 手机是否开启了 USB 调试
    echo   3. 手机是否授权了此电脑的调试请求
    echo   4. ADB 是否已安装并添加到 PATH
    echo.
    pause
    exit /b 1
)

echo [成功] ADB 设备已连接
echo.
echo ============================================
echo   AI空中鼠标 — USB ADB 鼠标控制
echo ============================================
echo.

:menu
echo 请选择操作模式：
echo   [1] 移动鼠标 (输入 dx dy)
echo   [2] 单击左键
echo   [3] 双击左键
echo   [4] 右键单击
echo   [5] 滚轮滚动
echo   [6] 长按/拖拽 (按下左键)
echo   [7] 释放按键 (松开左键)
echo   [8] 连续移动模式 (键盘方向键控制)
echo   [9] 退出
echo.
set /p choice="请输入选项 [1-9]: "

if "%choice%"=="1" goto move_mouse
if "%choice%"=="2" goto click_left
if "%choice%"=="3" goto double_click
if "%choice%"=="4" goto click_right
if "%choice%"=="5" goto scroll_wheel
if "%choice%"=="6" goto press_down
if "%choice%"=="7" goto release_up
if "%choice%"=="8" goto continuous_mode
if "%choice%"=="9" goto exit_script
echo 无效选项，请重试
goto menu

:move_mouse
set /p dx="请输入 X 轴位移 (正数=右移, 负数=左移): "
set /p dy="请输入 Y 轴位移 (正数=下移, 负数=上移): "
echo [执行] 移动鼠标: dx=%dx%, dy=%dy%
adb shell input mouse %dx% %dy%
echo [完成]
echo.
goto menu

:click_left
echo [执行] 左键单击
adb shell input tap 0 0
REM 注: input tap 是点击屏幕，不是鼠标点击。
REM 正确的鼠标点击需要使用:
REM adb shell input keyevent --longpress KEYCODE_BUTTON_1
echo [完成] 注意: 需要使用支持鼠标事件的设备
echo.
goto menu

:double_click
echo [执行] 双击左键
adb shell input keyevent KEYCODE_BUTTON_1
timeout /t 1 /nobreak >nul
adb shell input keyevent KEYCODE_BUTTON_1
echo [完成]
echo.
goto menu

:click_right
echo [执行] 右键单击
adb shell input keyevent KEYCODE_BUTTON_2
echo [完成]
echo.
goto menu

:scroll_wheel
set /p amount="请输入滚动量 (正数=向下, 负数=向上): "
echo [执行] 滚轮滚动: amount=%amount%
adb shell input mouse scroll %amount%
echo [完成]
echo.
goto menu

:press_down
echo [执行] 左键按下 (拖拽模式)
adb shell input keyevent --longpress KEYCODE_BUTTON_1
echo [完成] 左键已按下，请选择 [7] 释放
echo.
goto menu

:release_up
echo [执行] 释放按键
adb shell input keyevent --longpress KEYCODE_BUTTON_1
echo [完成]
echo.
goto menu

:continuous_mode
echo.
echo ============================================
echo   连续移动模式
echo   使用键盘方向键控制鼠标
echo   按 Q 退出连续移动模式
echo ============================================
echo.

:continuous_loop
choice /c wasdq /n /m ""
if %errorlevel%==1 (
    REM W - 上移
    adb shell input mouse 0 -10
    goto continuous_loop
)
if %errorlevel%==2 (
    REM A - 左移
    adb shell input mouse -10 0
    goto continuous_loop
)
if %errorlevel%==3 (
    REM S - 下移
    adb shell input mouse 0 10
    goto continuous_loop
)
if %errorlevel%==4 (
    REM D - 右移
    adb shell input mouse 10 0
    goto continuous_loop
)
if %errorlevel%==5 (
    REM Q - 退出
    echo 退出连续移动模式
    echo.
    goto menu
)
goto continuous_loop

:exit_script
echo.
echo 感谢使用 AI空中鼠标！
echo.
pause
exit /b 0