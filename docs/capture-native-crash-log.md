# Native Crash 日志抓取指南

> 用于排查 ffmpeg-kit 等 JNI/Native 层的段错误（SIGSEGV）和崩溃。

## 前置条件

- Android 设备（真机）与电脑在同一局域网
- 已安装 adb（Android SDK 自带）
- 应用已安装待调试版本（带符号表的 AAR）

---

## 步骤一：启用无线 ADB

### 方式 A：USB 首次配对（推荐）

```bash
# 1. 用 USB 连接手机，确认设备已识别
adb devices

# 2. 开启无线调试端口（5555 为默认）
adb tcpip 5555

# 3. 断开 USB，通过 WiFi 连接
adb connect 192.168.x.x:5555

# 4. 验证
adb devices
```

### 方式 B：手机端直接开启无线调试

1. 设置 → 开发者选项 → 无线调试 → 启用
2. 点击「使用配对码配对设备」
3. 电脑上执行：

```bash
adb pair 192.168.x.x:port 配对码
adb connect 192.168.x.x:port
```

---

## 步骤二：清空旧日志并开始抓取

```bash
# 清空设备上的旧 logcat 缓冲区
adb logcat -c

# 开始实时抓取，同时保存到本地文件
adb logcat -v threadtime *:V | tee crash_log.txt
```

参数说明：
- `-v threadtime`：显示时间戳、进程 ID、线程 ID
- `*:V`：打印所有日志（Verbose 级别），确保不遗漏 DEBUG/FATAL 信息
- `tee`：同时输出到屏幕和文件

---

## 步骤三：复现崩溃

在手机上操作应用，触发 native crash（例如连续两次视频压缩）。

崩溃时 logcat 中会输出 tombstone 信息，典型特征：

```
F/libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 12345 (RenderThread), pid 6789 (com.xxx)
DEBUG     : *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
DEBUG     : Build fingerprint: '...'
DEBUG     : Revision: '0'
DEBUG     : ABI: 'arm64'
DEBUG     : Timestamp: 2026-05-14 00:00:00+0800
DEBUG     : Process uptime: 10s
DEBUG     : pid: 6789, tid: 12345, name: RenderThread  >>> com.xxx <<<
DEBUG     : signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000000
DEBUG     : Cause: null pointer dereference
DEBUG     :     x0  0000000000000000  x1  0000007a8f4c5000  x2  0000000000000001  x3  0000000000000000
...
DEBUG     : backtrace:
DEBUG     :       #00 pc 0000000000045678  /data/app/.../lib/arm64/libavcodec.so (avcodec_decode_video2+1234)
DEBUG     :       #01 pc 0000000000034567  /data/app/.../lib/arm64/libavutil.so (...)
```

---

## 步骤四：提取关键信息

从 `crash_log.txt` 中搜索以下关键词，定位崩溃点：

```bash
# 搜索信号信息
grep -E "F/libc|Fatal signal|signal [0-9]+" crash_log.txt

# 搜索 tombstone 开头
grep -n "\*\*\* \*\*\*" crash_log.txt

# 搜索 backtrace
grep -n "backtrace" crash_log.txt
```

关键字段：

| 字段 | 含义 |
|------|------|
| `signal 11 (SIGSEGV)` | 段错误，非法内存访问 |
| `fault addr 0x0` | 访问的非法地址（0x0 = 空指针） |
| `pc 0000000000045678` | 崩溃时的程序计数器（指令地址） |
| `libavcodec.so (avcodec_xxx+1234)` | 崩溃所在的 so 和函数（需带符号表） |

---

## 步骤五：获取 tombstone 文件（可选，需 root）

如果设备已 root，可直接读取系统保存的 tombstone：

```bash
adb shell
su
cat /data/tombstones/tombstone_00
```

或通过 bugreport 导出：

```bash
adb bugreport bugreport.zip
# 解压后在 FS/data/tombstones/ 下查看
```

---

## 符号表还原（必需）

如果 AAR 的 `.so` 文件**带有符号表**，tombstone 中的 backtrace 会直接显示函数名和行号。

若只显示地址，需手动还原：

```bash
# 使用 NDK 提供的 llvm-symbolizer
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-symbolizer \
    --obj=libavcodec.so \
    --demangle \
    0x45678
```

或使用 `addr2line`：

```bash
aarch64-linux-android-addr2line -C -f -e libavcodec.so 0x45678
```

---

## 快速检查清单

- [ ] 无线 adb 已连接（`adb devices` 显示设备）
- [ ] logcat 已清空（`adb logcat -c`）
- [ ] 应用使用的是**带符号表的 debug AAR**
- [ ] 崩溃已复现，logcat 中出现了 `F/libc` 和 `DEBUG` 行
- [ ] 已保存完整 logcat 到本地文件
