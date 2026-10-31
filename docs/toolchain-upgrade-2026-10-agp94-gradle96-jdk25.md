# 构建工具链升级方案：AGP 9.4.0 / Gradle 9.6.1 / JDK 25 / compileSdk 37

- 日期：2026-10-31
- 状态：已实施（真机验证待做）
- 范围：构建工具链与 SDK 编译级别；**不动** `versionCode` / `versionName`，不发版

## 1. 目标

| 项 | 升级前 | 升级后 |
|---|---|---|
| AGP | 9.2.0 | **9.4.0** |
| Gradle | 9.4.1 | **9.6.1** |
| Gradle daemon JVM | Java 21（Gradle 托管下载的 JetBrains JRE） | **Java 25（`D:\dev\jdk-25`，Temurin 25.0.4.1+1-LTS）** |
| compileSdk | 36 | **37** |
| targetSdk | 36 | **37**（对齐 Android 17，启用新的运行时行为变更） |
| Java / Kotlin jvmTarget | 17 | 17（本次不动） |

版本选择原则：取**本机 Gradle 缓存中已存在的最高版本**，避免额外下载。

## 2. 约束取证（不是推测）

### 2.1 AGP 9.4.0 要求 Gradle ≥ 9.6.0

对缓存的 AGP jar 反编译取证：

```
com.android.build.gradle.internal.plugins.VersionCheckPlugin 静态初始化块：
   ldc "9.6.0" → GradleVersion.version(...) → putstatic GRADLE_MIN_VERSION
```

提示信息原文（同一类的常量池）：

```
Minimum supported Gradle version is <min>. Current version is <cur>.
Try updating the 'distributionUrl' property in <file> to 'gradle-<min>-bin.zip'.
```

**结论：AGP 与 Gradle 必须同批升级。** 只升 AGP 到 9.4.0 而 wrapper 留在 9.4.1，会在配置阶段直接失败。

### 2.2 Gradle 9.6.1 可在 JDK 25 上运行

- 空工程 `gradle help`（JDK 25 作 launcher + daemon）：BUILD SUCCESSFUL
- 最小 Android 工程（AGP 9.4.0 + compileSdk 36 + JVM target 17）在 JDK 25 上 `compileDebugKotlin`：BUILD SUCCESSFUL

### 2.3 Kotlin 版本不受本次升级影响

AGP 9.4.0 的 POM 依赖 `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10`（内置 Kotlin 路径），与 AGP 9.2.0 所用版本一致。因此 `android { kotlin { compilerOptions { jvmTarget } } }` 写法、语言版本均无需调整。

### 2.4 SDK 侧组件已就位

`D:\dev\android_sdk` 已有 `platforms/android-37.0` 与 `build-tools/37.0.0`，compileSdk 37 无需联网安装。

## 3. 本次最关键的坑：daemon JVM 被钉在 21

`gradle/gradle-daemon-jvm.properties` 由 `updateDaemonJvm` 任务生成，内容是 `toolchainVersion=21`。**该文件的优先级高于 `JAVA_HOME`**：即使把 `JAVA_HOME` 指向 `D:\dev\jdk-25`，Gradle 仍会按 21 寻找/下载 daemon JVM（升级前实际使用的是 `~/.gradle/jdks/jetbrains_s_r_o_-21`，即 Gradle 自动下载的 JetBrains JRE 21）。

若只改 `JAVA_HOME` 而忽略该文件，会出现"看似换了 JDK、实际仍在 21 上编译"的假象。处理方式：

1. `toolchainVersion` 由 21 改为 25；
2. `toolchainUrl.*`（foojay 下载地址）原本绑定的是 **JDK 21 的具体发行版**，直接改数字会造成"版本写 25、下载地址却是 21"的错配。标准做法是 `./gradlew updateDaemonJvm --jvm-version=25` 重新生成，但该任务在未配置 toolchain 下载仓库时直接失败：

   ```
   Invalid task configuration
     Toolchain download repositories have not been configured.
   ```

   要让它工作，得在 `settings.gradle.kts` 引入 `org.gradle.toolchains.foojay-resolver-convention` 插件并新增一个联网下载源——本项目仓库全走阿里云镜像、且要求离线可构建，为此引入 foojay 不划算。
   **最终做法**：手工维护该文件，保留 `toolchainVersion=25`、删除全部 `toolchainUrl.*`。代价是本机必须自带 JDK 25（`JAVA_HOME` 指向即可被自动探测），Gradle 不再自动下载。这个取舍对单机/离线构建更稳，失败时的报错也比"悄悄下载一个陌生 JDK"更可诊断；
3. 构建时仍需设置 `JAVA_HOME=D:\dev\jdk-25`，Gradle 的 JVM 自动探测包含 `JAVA_HOME`，可命中本机已装 JDK 25，不触发下载；
4. **不使用** `org.gradle.java.installations.paths`——该配置在 Termux 手机构建端会因路径不存在产生告警。

## 4. 改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `gradle/wrapper/gradle-wrapper.properties` | `distributionUrl` → `gradle-9.6.1-bin.zip`（腾讯云镜像同路径） |
| 2 | `build.gradle.kts` | `com.android.application` 版本 9.2.0 → 9.4.0 |
| 3 | `app/build.gradle.kts` | `compileSdk = 36` → `37`，`targetSdk = 36` → `37` |
| 4 | `gradle/gradle-daemon-jvm.properties` | `toolchainVersion=25`，并删除 foojay `toolchainUrl.*`（详见第 3 节） |
| 5 | `AGENTS.md` | 构建环境 `JAVA_HOME`/`PATH`、回归测试段、Build Configuration 版本段、测试设施描述（原写"仓库没有任何测试设施"已过期） |
| 6 | `docs/build-on-termux-ubuntu.md` | 主线 JDK 说明；历史 AGP 8.9.1 组合加注记（不盲改历史结论） |
| 7 | `build-termux.sh` | 注释中的 Gradle 版本 |
| 8 | `CHANGELOG.md` | 新增 `[Unreleased]` → `### 构建` 条目 |
| 9 | `.workbuddy/memory/MEMORY.md` | 构建系统版本描述 |

不改：`README.md` / `README.zh.md`（无版本号引用）、`.qoder/repowiki/`（AI 生成 wiki，非权威）、`build.sh` 中 `build-tools/36.0.0` 的 zipalign/apksigner 路径（签名工具独立于 AGP，可继续用）。

## 5. 验证链

| 步骤 | 命令 | 验证目标 |
|---|---|---|
| 1 | `./gradlew --version` | Gradle 9.6.1 + `Daemon JVM: Java 25`（专验第 3 节的坑） |
| 2 | `./gradlew :app:assembleDebug` | D8 / core library desugaring 在 JDK 25 + compileSdk 37 下正常 |
| 3 | `./gradlew :app:testDebugUnitTest` | 旋转矩阵回归（`Mp4RotationUtils`，约 0.3 s） |
| 4 | `./gradlew :app:assembleRelease` | R8 minify + resource shrink 在 JDK 25 下正常 |
| 5 | `java -jar build-tools/36.0.0/lib/apksigner.jar sign ...` | 用 JDK 25 跑 apksigner 签名正常 |
| 6 | 真机安装 + 裁剪/合并/压缩/旋转/音频编辑逐项走一遍 | 功能面无回归 |

第 1–5 步由构建侧完成；第 6 步由本人在真机执行，通过后才考虑发版（届时另起版本号与 tag）。

本机 shell 的 `PATH` 被裁剪，执行前需：

```powershell
$env:JAVA_HOME = "D:\dev\jdk-25"
$env:PATH = "D:\dev\jdk-25\bin;$env:PATH"
$env:ANDROID_HOME = "D:\dev\android_sdk"
```

## 6. 风险与回退

| 风险 | 说明 | 处置 |
|---|---|---|
| R8 / D8 在 JDK 25 上的行为 | 工具本身是纯 Java，风险低但未覆盖最小工程验证 | 用 `assembleRelease` + `assembleDebug` 实测；失败则回退 Gradle/AGP |
| compileSdk / targetSdk 37 的行为变更 | 新 API 面可能触发新的 lint 告警；targetSdk 升 37 会启用 Android 17 的运行时行为变更（存储、权限、后台限制等），编译通过不等于运行时无差异 | 编译侧由 `assembleDebug` / `assembleRelease` 覆盖；运行时差异靠真机回归。如 lint 阻塞则加 `lint { abortOnError = false }` 属临时手段，须另立 issue |
| Termux aarch64 构建 | 手机端未必有 openjdk-25 包，`toolchainVersion=25` 会导致 daemon 无法启动 | 已知限制：Termux 构建非主线，需临时移除 `gradle-daemon-jvm.properties` 或安装 25 后再用 |
| 配置缓存在大版本切换后失效 | Gradle 升级后 CC 目录不兼容，首次构建全量重建 | 预期行为，不是故障 |
| apksigner 走 `PATH` 上的 java | `build.sh` 里是 `java -jar`，若 PATH 上仍是旧 JDK 可能异常 | 实测已验证 JDK 25 可正常签名 |

**回退方式**（四处均为版本号级改动，无数据/缓存污染）：

```bash
git checkout -- gradle/wrapper/gradle-wrapper.properties \
                gradle/gradle-daemon-jvm.properties \
                build.gradle.kts app/build.gradle.kts
```

## 7. 未纳入本次范围

- 发版（`versionCode` / `versionName` / tag / GitHub Release）：等真机验证通过后另做
- Termux 端工具链对齐
- 依赖库（AndroidX / media3 / desugar_jdk_libs）升级

## 8. 实测结果（2026-10-31）

| 步骤 | 结果 | 关键输出 |
|---|---|---|
| `./gradlew --version` | 通过 | `Gradle 9.6.1`，`Daemon JVM: Compatible with Java 25, any vendor`；wrapper 复用本地缓存分发，未重新下载 |
| `./gradlew :app:assembleDebug` | 通过（32 s） | `compileDebugKotlin` / `compileDebugJavaWithJavac` / `desugarDebugFileDependencies` / `dexBuilderDebug` 全部执行成功；仅原有 6 条 Kotlin 弃用/命名告警 |
| `./gradlew :app:testDebugUnitTest` | 通过（3 s） | `tests="15" skipped="1" failures="0" errors="0"`（skip 的是需要 `test_data/` 的真实素材用例） |
| `./gradlew :app:assembleRelease` | 通过（75 s） | `minifyReleaseWithR8`、`convertShrunkResourcesToBinaryRelease`、`lintVitalRelease`、`l8DexDesugarLibRelease` 全部成功 |
| APK 元数据 | 符合预期 | `compileSdkVersion='37'`、`targetSdkVersion:'37'`（`targetSdk` 随后一并升 37 并重新构建）、`versionCode='13'`、`versionName='2.2.1'`（未发版）、`platformBuildVersionName='17'` |
| daemon 进程取证 | 符合预期 | `Get-CimInstance Win32_Process` 显示新 daemon 的 java 路径为 `D:\dev\jdk-25\bin\java.exe`；升级前为 `.gradle\jdks\jetbrains_s_r_o_-21` |
| apksigner（build-tools 36.0.0 / 37.0.0） | 通过 | `zipalign` + `sign` + `verify` 均 `exit=0`；JDK 25 会打印 JEP 472 原生访问告警（`org.conscrypt.NativeLibraryUtil` 调用 `System.loadLibrary`），属噪声，不影响产物。若嫌吵可给 java 加 `--enable-native-access=ALL-UNNAMED`（该选项 JDK 24+ 才有效，本机 `build.sh` 用 PATH 上的 `java`，故未改） |

真机功能验证（裁剪 / 合并 / 压缩 / 旋转 / 音频编辑）尚未执行，通过后才进入发版流程。

