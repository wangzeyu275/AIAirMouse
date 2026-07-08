#!/bin/bash
# ============================================================
#  AI空中鼠标 — USB ADB 鼠标控制脚本 (macOS/Linux)
#
#  方案说明：
#    基于 ADB 指令模拟鼠标，利用 adb shell input mouse 命令
#    控制电脑指针。无需 root，电脑端仅需安装 ADB 驱动。
#
#  前提条件：
#    1. 电脑已安装 ADB (Android Debug Bridge)
#       macOS: brew install android-platform-tools
#       Linux: sudo apt install adb  (或从 SDK 下载)
#    2. 手机已开启 "USB调试" 模式
#    3. 手机通过 USB 数据线连接到电脑
#    4. 手机已授权电脑的 USB 调试请求
#
#  延迟对比：
#    ADB input 方案延迟: ~15-30ms
#    HID Gadget 方案延迟:  ~1-5ms
#    蓝牙 HID 方案延迟:    ~8-15ms
#
#  使用方法：
#    chmod +x usb_adb_mouse.sh
#    ./usb_adb_mouse.sh
# ============================================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# 检查 ADB 是否安装
check_adb() {
    if ! command -v adb &> /dev/null; then
        echo -e "${RED}[错误] 未找到 ADB 命令！${NC}"
        echo ""
        echo "请先安装 Android Platform Tools:"
        echo "  macOS: brew install android-platform-tools"
        echo "  Linux: sudo apt install adb"
        echo ""
        exit 1
    fi
}

# 检查 ADB 设备连接
check_device() {
    echo -e "${CYAN}[检查] 正在检查 ADB 设备连接...${NC}"
    local devices=$(adb devices 2>/dev/null | grep -v "List" | grep "device$" | wc -l)
    if [ "$devices" -eq 0 ]; then
        echo -e "${RED}[错误] 未检测到 ADB 设备！${NC}"
        echo ""
        echo "请检查："
        echo "  1. 手机是否通过 USB 连接到电脑"
        echo "  2. 手机是否开启了 USB 调试"
        echo "  3. 手机是否授权了此电脑的调试请求"
        echo ""
        exit 1
    fi
    echo -e "${GREEN}[成功] ADB 设备已连接${NC}"
}

# 移动鼠标
move_mouse() {
    echo -n "请输入 X 轴位移 (正数=右移, 负数=左移): "
    read dx
    echo -n "请输入 Y 轴位移 (正数=下移, 负数=上移): "
    read dy
    echo -e "${CYAN}[执行] 移动鼠标: dx=$dx, dy=$dy${NC}"
    adb shell input mouse $dx $dy
    echo -e "${GREEN}[完成]${NC}"
}

# 左键单击
click_left() {
    echo -e "${CYAN}[执行] 左键单击${NC}"
    adb shell input keyevent KEYCODE_BUTTON_1
    echo -e "${GREEN}[完成]${NC}"
}

# 右键单击
click_right() {
    echo -e "${CYAN}[执行] 右键单击${NC}"
    adb shell input keyevent KEYCODE_BUTTON_2
    echo -e "${GREEN}[完成]${NC}"
}

# 滚轮滚动
scroll_wheel() {
    echo -n "请输入滚动量 (正数=向下, 负数=向上): "
    read amount
    echo -e "${CYAN}[执行] 滚轮滚动: amount=$amount${NC}"
    adb shell input mouse scroll $amount
    echo -e "${GREEN}[完成]${NC}"
}

# 连续移动模式
continuous_mode() {
    echo ""
    echo -e "${YELLOW}============================================${NC}"
    echo -e "${YELLOW}  连续移动模式${NC}"
    echo -e "${YELLOW}  使用 WASD 键控制鼠标移动${NC}"
    echo -e "${YELLOW}  按 Q 退出连续移动模式${NC}"
    echo -e "${YELLOW}============================================${NC}"
    echo ""

    # 保存终端设置
    old_tty=$(stty -g)
    stty raw -echo min 0 time 0

    local step=10
    while true; do
        local key=$(dd bs=1 count=1 2>/dev/null)
        case "$key" in
            w|W) adb shell input mouse 0 -$step 2>/dev/null ;;
            a|A) adb shell input mouse -$step 0 2>/dev/null ;;
            s|S) adb shell input mouse 0 $step 2>/dev/null ;;
            d|D) adb shell input mouse $step 0 2>/dev/null ;;
            q|Q) break ;;
            $'\x1b') # ESC
                read -t 0.1 -n 2 escape_seq
                break
                ;;
        esac
    done

    # 恢复终端设置
    stty "$old_tty"
    echo ""
    echo -e "${GREEN}退出连续移动模式${NC}"
}

# 显示菜单
show_menu() {
    echo ""
    echo "============================================"
    echo "  AI空中鼠标 — USB ADB 鼠标控制"
    echo "============================================"
    echo ""
    echo "请选择操作模式："
    echo "  [1] 移动鼠标 (输入 dx dy)"
    echo "  [2] 单击左键"
    echo "  [3] 右键单击"
    echo "  [4] 滚轮滚动"
    echo "  [5] 连续移动模式 (WASD 控制)"
    echo "  [6] 退出"
    echo ""
}

# 主循环
main() {
    check_adb
    check_device

    while true; do
        show_menu
        read -p "请输入选项 [1-6]: " choice

        case $choice in
            1) move_mouse ;;
            2) click_left ;;
            3) click_right ;;
            4) scroll_wheel ;;
            5) continuous_mode ;;
            6)
                echo ""
                echo -e "${GREEN}感谢使用 AI空中鼠标！${NC}"
                exit 0
                ;;
            *)
                echo -e "${RED}无效选项，请重试${NC}"
                ;;
        esac
    done
}

main