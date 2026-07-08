/*
 * ============================================================
 *  AI空中鼠标 — USB HID Gadget 方案 (Root 设备专用)
 *
 *  方案说明：
 *    利用 Linux 内核的 USB Gadget 框架，将 Android 手机的 USB
 *    控制器配置为 HID 鼠标设备。手机在硬件层面被电脑识别为有
 *    线鼠标，实现零依赖、极低延迟（~1-5ms）的鼠标控制。
 *
 *  前提条件：
 *    1. Android 设备已 Root
 *    2. 内核支持 USB Gadget 配置 (CONFIG_USB_GADGET=y)
 *    3. 内核支持 FunctionFS 或 ConfigFS
 *    4. 手机支持 USB OTG 并可以切换到 Device/Peripheral 模式
 *
 *  延迟对比：
 *    HID Gadget 方案:  ~1-5ms  (内核级，零拷贝，零依赖)
 *    ADB input 方案:   ~15-30ms
 *    蓝牙 HID 方案:    ~8-15ms
 *
 *  优缺点：
 *    优点: 延迟最低，电脑端零依赖，稳定可靠
 *    缺点: 需要 Root，需要内核支持，配置复杂
 * ============================================================
 */

/*
 * ============================================================
 *  方案一：通过 ConfigFS 配置 USB HID Gadget
 *
 *  此方法通过写入 sysfs 文件来配置 USB Gadget，无需额外内核模块。
 *  在已 Root 的 Android 设备上执行以下 shell 命令：
 * ============================================================
 *
 * # 1. 加载必要的内核模块
 * insmod /system/lib/modules/usb_f_hid.ko
 * insmod /system/lib/modules/u_ether.ko  # 可选
 *
 * # 2. 配置 USB Gadget
 * cd /sys/kernel/config/usb_gadget
 * mkdir g1
 * cd g1
 *
 * # 3. 设置 USB 设备描述符
 * echo 0x046d > idVendor     # Logitech (通用鼠标厂商ID)
 * echo 0xc077 > idProduct     # M185 鼠标 (通用产品ID)
 * echo 0x0200 > bcdDevice     # USB 2.0
 * echo 0x0200 > bcdUSB
 *
 * # 4. 设置设备字符串
 * mkdir strings/0x409
 * echo "AI Air Mouse" > strings/0x409/manufacturer
 * echo "AI Air Mouse HID" > strings/0x409/product
 * echo "000000000001" > strings/0x409/serialnumber
 *
 * # 5. 配置 HID 功能
 * mkdir functions/hid.usb0
 * echo 0 > functions/hid.usb0/protocol     # 0=Boot Protocol (鼠标)
 * echo 0 > functions/hid.usb0/subclass     # 0=No Subclass
 * echo 4 > functions/hid.usb0/report_length # 4字节报告
 *
 * # 6. 写入 HID 报告描述符 (与蓝牙方案相同的描述符)
 * # 使用 echo -ne 写入二进制数据
 * echo -ne '\x05\x01\x09\x02\xa1\x01\x09\x01\xa1\x00\x05\x09\x19\x01\x29\x03\x15\x00\x25\x01\x95\x03\x75\x01\x81\x02\x95\x01\x75\x05\x81\x03\x05\x01\x09\x30\x09\x31\x09\x38\x15\x81\x25\x7f\x75\x08\x95\x03\x81\x06\xc0\xc0' > functions/hid.usb0/report_desc
 *
 * # 7. 绑定配置
 * mkdir configs/c.1
 * mkdir configs/c.1/strings/0x409
 * echo "HID Mouse Config" > configs/c.1/strings/0x409/configuration
 * ln -s functions/hid.usb0 configs/c.1/
 *
 * # 8. 启用 Gadget
 * echo $(ls /sys/class/udc) > UDC
 *
 * # 9. 发送鼠标数据 (写入 HID 报告)
 * # 报告格式 (4字节):
 * #   Byte 0: 按钮 [bit0=左键, bit1=右键, bit2=中键]
 * #   Byte 1: X 轴位移 (signed)
 * #   Byte 2: Y 轴位移 (signed)
 * #   Byte 3: 滚轮 (signed)
 *
 * # 移动鼠标: dx=10, dy=0
 * echo -ne '\x00\x0a\x00\x00' > /dev/hidg0
 *
 * # 左键单击
 * echo -ne '\x01\x00\x00\x00' > /dev/hidg0  # 按下
 * sleep 0.05
 * echo -ne '\x00\x00\x00\x00' > /dev/hidg0  # 释放
 */

/*
 * ============================================================
 *  方案二：C 语言控制程序 (通过 /dev/hidg0 设备节点)
 *
 *  编译方法 (在 Android 上使用 NDK 交叉编译):
 *    aarch64-linux-android-gcc -static -o hidmouse hidmouse.c
 *
 *  运行:
 *    adb push hidmouse /data/local/tmp/
 *    adb shell chmod 755 /data/local/tmp/hidmouse
 *    adb shell /data/local/tmp/hidmouse
 * ============================================================
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <stdint.h>

#define HID_DEVICE "/dev/hidg0"
#define REPORT_SIZE 4

/* 鼠标按钮位掩码 */
#define BTN_LEFT    0x01
#define BTN_RIGHT   0x02
#define BTN_MIDDLE  0x04

/* 输出调试信息 */
#define DEBUG 1

#if DEBUG
#define LOG(fmt, ...) fprintf(stderr, "[HIDMouse] " fmt "\n", ##__VA_ARGS__)
#else
#define LOG(fmt, ...)
#endif

/**
 * 发送 HID 鼠标报告。
 *
 * @param fd      设备文件描述符
 * @param buttons 按钮状态 (BTN_LEFT | BTN_RIGHT | BTN_MIDDLE)
 * @param dx      X 轴相对位移 (-127 ~ 127)
 * @param dy      Y 轴相对位移 (-127 ~ 127)
 * @param wheel   滚轮位移 (-127 ~ 127)
 * @return 成功返回 0，失败返回 -1
 */
static int send_report(int fd, uint8_t buttons, int8_t dx, int8_t dy, int8_t wheel) {
    uint8_t report[REPORT_SIZE];
    report[0] = buttons;
    report[1] = (uint8_t)dx;
    report[2] = (uint8_t)dy;
    report[3] = (uint8_t)wheel;

    ssize_t written = write(fd, report, REPORT_SIZE);
    if (written != REPORT_SIZE) {
        LOG("写入HID报告失败: %s (写入 %zd/%d 字节)", strerror(errno), written, REPORT_SIZE);
        return -1;
    }
    return 0;
}

/**
 * 移动鼠标指针。
 */
static int move_cursor(int fd, int dx, int dy) {
    /* 限制在 signed byte 范围 */
    if (dx > 127) dx = 127;
    if (dx < -127) dx = -127;
    if (dy > 127) dy = 127;
    if (dy < -127) dy = -127;

    return send_report(fd, 0, (int8_t)dx, (int8_t)dy, 0);
}

/**
 * 单击左键。
 */
static int click_left(int fd) {
    if (send_report(fd, BTN_LEFT, 0, 0, 0) < 0) return -1;
    usleep(50000); /* 50ms */
    return send_report(fd, 0, 0, 0, 0);
}

/**
 * 单击右键。
 */
static int click_right(int fd) {
    if (send_report(fd, BTN_RIGHT, 0, 0, 0) < 0) return -1;
    usleep(50000);
    return send_report(fd, 0, 0, 0, 0);
}

/**
 * 滚轮滚动。
 */
static int scroll_wheel(int fd, int amount) {
    if (amount > 127) amount = 127;
    if (amount < -127) amount = -127;

    if (send_report(fd, 0, 0, 0, (int8_t)amount) < 0) return -1;
    usleep(30000); /* 30ms */
    return send_report(fd, 0, 0, 0, 0);
}

/**
 * 画圆演示 (测试用)。
 */
static void demo_circle(int fd) {
    LOG("演示: 画圆移动");
    int radius = 20;
    int steps = 36;
    for (int i = 0; i < steps * 3; i++) {
        double angle = (double)i / steps * 2.0 * 3.14159;
        int dx = (int)(radius * cos(angle));
        int dy = (int)(radius * sin(angle));
        move_cursor(fd, dx, dy);
        usleep(16000); /* ~60Hz */
    }
}

/**
 * 打印使用说明。
 */
static void print_usage(const char *prog) {
    printf("AI空中鼠标 — USB HID Gadget 控制程序\n");
    printf("用法: %s <命令> [参数]\n\n", prog);
    printf("命令:\n");
    printf("  move <dx> <dy>    移动鼠标指针\n");
    printf("  click             左键单击\n");
    printf("  rclick            右键单击\n");
    printf("  scroll <amount>   滚轮滚动\n");
    printf("  demo              画圆演示\n");
    printf("  interactive       交互式控制模式\n");
    printf("\n");
    printf("示例:\n");
    printf("  %s move 10 -5      向右移动10，向上移动5\n", prog);
    printf("  %s click           单击左键\n", prog);
    printf("  %s scroll -3       向上滚动3格\n", prog);
}

/**
 * 交互式控制模式。
 */
static void interactive_mode(int fd) {
    printf("\n============================================\n");
    printf("  AI空中鼠标 — 交互式控制模式\n");
    printf("  WASD: 移动  |  Q: 左键  |  E: 右键\n");
    printf("  R/F: 滚轮   |  X: 退出\n");
    printf("============================================\n\n");

    /* 设置终端为非缓冲模式 */
    system("stty raw -echo");

    int step = 10;
    char c;
    while (read(STDIN_FILENO, &c, 1) == 1) {
        switch (c) {
            case 'w': move_cursor(fd, 0, -step); break;
            case 'a': move_cursor(fd, -step, 0); break;
            case 's': move_cursor(fd, 0, step); break;
            case 'd': move_cursor(fd, step, 0); break;
            case 'q': click_left(fd); break;
            case 'e': click_right(fd); break;
            case 'r': scroll_wheel(fd, 1); break;
            case 'f': scroll_wheel(fd, -1); break;
            case 'x': goto exit_interactive;
            case 'W': move_cursor(fd, 0, -step * 3); break;
            case 'A': move_cursor(fd, -step * 3, 0); break;
            case 'S': move_cursor(fd, 0, step * 3); break;
            case 'D': move_cursor(fd, step * 3, 0); break;
        }
    }

exit_interactive:
    /* 恢复终端设置 */
    system("stty cooked echo");
    printf("\n退出交互模式\n");
}

int main(int argc, char *argv[]) {
    if (argc < 2) {
        print_usage(argv[0]);
        return 1;
    }

    /* 打开 HID 设备 */
    int fd = open(HID_DEVICE, O_WRONLY);
    if (fd < 0) {
        LOG("无法打开 %s: %s", HID_DEVICE, strerror(errno));
        LOG("请确认 USB HID Gadget 已正确配置");
        LOG("参考: 使用 ConfigFS 脚本配置 USB Gadget");
        return 1;
    }

    const char *cmd = argv[1];
    int result = 0;

    if (strcmp(cmd, "move") == 0) {
        if (argc < 4) {
            LOG("move 命令需要 dx 和 dy 参数");
            result = 1;
        } else {
            int dx = atoi(argv[2]);
            int dy = atoi(argv[3]);
            result = move_cursor(fd, dx, dy);
        }
    } else if (strcmp(cmd, "click") == 0) {
        result = click_left(fd);
    } else if (strcmp(cmd, "rclick") == 0) {
        result = click_right(fd);
    } else if (strcmp(cmd, "scroll") == 0) {
        if (argc < 3) {
            LOG("scroll 命令需要 amount 参数");
            result = 1;
        } else {
            int amount = atoi(argv[2]);
            result = scroll_wheel(fd, amount);
        }
    } else if (strcmp(cmd, "demo") == 0) {
        demo_circle(fd);
    } else if (strcmp(cmd, "interactive") == 0) {
        interactive_mode(fd);
    } else {
        LOG("未知命令: %s", cmd);
        print_usage(argv[0]);
        result = 1;
    }

    close(fd);
    return result;
}