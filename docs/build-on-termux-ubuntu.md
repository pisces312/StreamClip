# 在 Termux + proot Ubuntu 上编译 StreamClip

> 适用场景：用 Android 手机自己编自己用的 APK（aarch64），不依赖电脑。

## 0. 环境概览

- 宿主：Android 手机（aarch64）
- Termux 内通过 proot 跑 **Ubuntu 24.04 (noble) aarch64**
- 项目放在外部存储 `/mnt/sdcard/my-git-projects/StreamClip/`（`/storage/emulated/0`、`/sdcard` 是同一位置）

## 1. proot ubuntu 内安装基础包

```bash
sudo apt update
sudo apt install -y \
  openjdk-17-jdk-headless openjdk-21-jdk-headless \
  android-sdk android-sdk-platform-tools \
  unzip curl git
```

注：

- **JDK 17**：项目源码 `jvmTarget = 17`，AGP 用它跑 javac/kotlinc。
- **JDK 21**：**旧版** Gradle 9.4.1 daemon 要求 21。当前主线已升到 Gradle 9.6.1，`gradle/gradle-daemon-jvm.properties` 要求 **JDK 25**；Debian 仓库尚无 `openjdk-25-jdk`，手机端得临时删除该文件（或改成 21）才能构建——见第 6 节。
- Debian 仓库里 `android-sdk-build-tools` 只有 **29.0.3**，且用不了——后面会用社区 aarch64 版替换。
- `android-sdk-platform-tools` 提供 aarch64 版 adb，OK。

## 2. 装 Android SDK（aarch64）

Google 不发 aarch64 Linux 的 build-tools，要从社区 fork 拿。**社区最新只到 35.0.2**（lzhiyong / woaiyuzi / erinor 三家都停在 35）。`compileSdk = 36` 的 `android.jar` 是纯 Java，可单独从 Google 镜像下。

```bash
mkdir -p $HOME/android-sdk/build-tools $HOME/android-sdk/platforms

# === build-tools 35.0.2 (aarch64 native) ===
curl -L -o /tmp/sdk-tools.zip \
  https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
unzip -q /tmp/sdk-tools.zip -d /tmp/sdk-tools

# 用 Debian 包提供的 35.0.0 jar/脚本作为基底（apksigner.jar、d8.jar 等架构无关）
# 然后用 aarch64 native 二进制覆盖（aapt2/aapt/zipalign/aidl/dexdump/split-select）
# 如果没装 Debian 的 build-tools，可手动从 https://dl.google.com/android/repository/build-tools_r35-linux.zip 拿 jar 部分
sudo cp -r /usr/lib/android-sdk/build-tools/29.0.3 $HOME/android-sdk/build-tools/35.0.0
# ↑ 注意：版本号写 35.0.0 是因为当时的 AGP 8.9.1 期望该版本。如果用 35.0.2 也行，但要在 build.gradle.kts 里显式声明
#   当前主线是 AGP 9.4.0 + compileSdk 37，本节的 aarch64 build-tools 组合尚未在 9.4.0 上验证（见第 6 节）
sudo chown -R $USER:$USER $HOME/android-sdk
for f in aapt aapt2 aidl zipalign dexdump split-select; do
  cp -f /tmp/sdk-tools/build-tools/$f $HOME/android-sdk/build-tools/35.0.0/$f
done

# === platform-36 (android.jar 纯 Java) ===
# 注意：这一段对应旧主线（AGP 8.9 / compileSdk 36）。当前主线 compileSdk 已升到 37，
# 需替换为对应的 platform-37 包（同目录下 platform-37_rXX.zip，具体 revision 需按镜像实际文件名核对，
# 不要照抄下面的 36 写法）。平台目录名在 SDK 里是 `android-37.0` 形式。
curl -L -o /tmp/platform-36.zip https://mirrors.cloud.tencent.com/AndroidSDK/platform-36_r02.zip
unzip -q /tmp/platform-36.zip -d /tmp/p36
mv /tmp/p36/android-36 $HOME/android-sdk/platforms/android-36
```

验证：

```bash
file $HOME/android-sdk/build-tools/35.0.0/aapt2   # 应显示 ELF ... arm64
ls $HOME/android-sdk/platforms/android-36/android.jar
```

## 3. 配置环境变量

写到 `~/.bashrc`：

```bash
cat >> ~/.bashrc << 'EOF'

# Android dev environment
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export JAVA_HOME_17=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=$HOME/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

# Release signing (StreamClip)
export STREAMCLIP_KEYSTORE=/mnt/sdcard/my-git-projects/my-backup/backup-settings/my-android-release.keystore
export KEY_ALIAS=pisces312
# export KEY_STORE_PASSWORD=...   # 不想明文写 .bashrc 可用 source ~/.private_env 方式
EOF
source ~/.bashrc
```

## 4. 项目本地配置

`local.properties`（已被 `.gitignore` 忽略，不进版本库）：

```properties
sdk.dir=/home/claudeuser/android-sdk
```

仓库已经把 Gradle/Maven 镜像都改成国内：

- `gradle/wrapper/gradle-wrapper.properties` → 腾讯
- `settings.gradle.kts` → 阿里 (plugins) + 阿里 (deps)

## 5. 构建

调用仓库自带的 `build-termux.sh`：

```bash
# debug
bash ./build-termux.sh debug

# release（需要 KEY_STORE_PASSWORD/KEY_ALIAS/STREAMCLIP_KEYSTORE 三个环境变量）
KEY_STORE_PASSWORD=xxx bash ./build-termux.sh release
```

成功后产物落在项目根目录：

```
StreamClip-v2.2.0-debug.apk
StreamClip-v2.2.0-release-signed.apk
```

由于 sdcard 上的目录就是手机外部存储，用文件管理器进入 `内部存储/my-git-projects/StreamClip/` 直接点击 APK 即可安装。

## 6. 已知坑

### sdcard 不支持 chmod +x
`./gradlew` 会因为执行位 0 报 `Permission denied`。**用 `bash ./gradlew` 调用**，脚本内已处理。

### AGP 默认下 x86_64 aapt2
AGP 8.x 启动时从 Maven 下一个 `aapt2-<ver>-linux.jar`，里面的二进制是 x86_64，aarch64 上跑不起来：

```
> AAPT2 ... Daemon startup failed
```

修复：传 `-Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2`，让 AGP 用本地 aarch64 版。脚本已默认带上。

### Gradle daemon 被 OOM-killer 杀
现象：`Gradle build daemon disappeared unexpectedly`。手机内存小、Android oom-killer 比较激进。

修复：
- `--no-daemon`：每次构建用一次性 JVM，避免长驻被杀
- `--max-workers=1`：单 worker 串行
- 堆大小：debug 用 `-Xmx1536m` 够；**release 必须 ≥ 4G**（R8 minify 吃内存）。脚本已按构建类型自动切换。

### 网络断流
GitHub 与 services.gradle.org 经常断。

- Gradle 走腾讯镜像（已改）
- AGP / Maven 走阿里镜像（已改）
- 下别的二进制（lzhiyong release、Google platform 包）尽量挂代理或腾讯镜像

### compileSdk 37 但 aarch64 build-tools 只有 35
AGP 8.9 + buildTools 35.0.0 + platform-36 曾是验证过的可用组合；主线现在已是 **AGP 9.4.0 + compileSdk 37 + Gradle 9.6.1 + JDK 25**，该组合只在 x86_64 Windows 上验证过，手机端未验证。社区仍无 aarch64 build-tools 36/37。

手机端若要跟上主线，需额外处理两件事：

1. **daemon JVM**：`gradle/gradle-daemon-jvm.properties` 要求 JDK 25，而 Debian/Termux 无此包 → 临时把该文件改名（Gradle 回落到 `JAVA_HOME` 的 21）再构建；
2. **platform**：需把 `platforms/android-37` 的 `android.jar` 单独放到 SDK 目录（纯 Java，可跨架构）。

## 7. 安装到手机
- **debug**：直接点 APK 安装。包名带 `.debug` 后缀，与已装的 release 版可共存。
- **release**：与 Play Store / 应用市场版本签名一致时会按"更新"安装；否则需先卸载老版本。

## 8. 常用快捷命令

```bash
# 查看连接的设备（如果开 USB 调试 / wifi adb）
adb devices

# 直接装到设备
adb install -r StreamClip-v2.1.2-full-debug.apk

# 查看日志
adb logcat -s StreamClip:V FFmpegKit:V
```
