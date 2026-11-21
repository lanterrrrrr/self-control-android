# 自控力 · Android 0.1.2

个人用的 Android 应用限额工具。支持 Android 12 及以上，首版按单窗口、亮屏解锁时的前台使用量计时。监管规则、用时、挑战文本和放行记录保存在本机。

## 使用方法

打开「自控力」，从首页进入系统设置，开启本应用的无障碍服务和使用情况访问。点击「添加应用」为选中的 App 设置每日额度。额度用完时，每个 App 各有一次连续倒计时的「最后 2 分钟」。通过朗读、打字或一道整数算术题后，对应 App 可再使用 5 分钟前台时长。各 App 共用每天两次挑战成功额度。日期按首次运行时设备的时区结算。

到限后，拦截页按剩余资格显示「最后 2 分钟」和「完成挑战」，并提供「返回桌面」。挑战页也可直接退出。

紧急时，从自控力首页进入 **设置 → 高级设置 → 紧急解除**，选择紧急暂停并确认一次。紧急解除页可以打开系统无障碍设置，也可以直接从 **Android 设置 → 无障碍 → 自控力** 关闭服务。覆盖层只针对受管应用的前台界面。设置、拨号、桌面及系统导航可以独立操作。Android 侧载限制因机型而异，系统若提示「受限设置」，由设备使用者在应用信息页按系统提示手动处理。

## 项目目录与清理

| 路径 | 内容与处理方式 |
| --- | --- |
| `android/app/src/main/` | Android 全部源代码、资源及 200 条中文短句。 |
| `android/tests/` | 可用 JDK 单独运行的规则与挑战测试。 |
| `tools/` | Windows 构建脚本。 |
| `artifacts/` | 已签名的测试 APK，以及模拟器和真机的验收记录。 |
| `.work/build/`、`.work/tmp/`、`.work/upstream/` | 构建中间文件、临时文件和开源调研副本。可以在工具停止运行后清理。 |
| `.work/dependencies/` | Vosk、JNA 和中文模型的下载缓存。删除后，构建时会重新下载并校验。 |
| `.work/emulator/` | 独立测试虚拟机、Android 虚拟机中安装的测试 APK 与测试数据、模拟器日志。虚拟机停止后可按需要删除。 |
| `.work/verification/` | 模拟器测试的合成语音、注入脚本和辅助产物。 |
| `.work/signing/` | 本地测试签名密钥。要用后续构建覆盖安装同一 App 并保留其数据，应将此目录一并保留。 |

构建复用电脑上已安装的 Android SDK 和 JDK。模拟器复用 SDK 中的 Google Play 系统镜像。手机或虚拟机上的应用安装程序和内部数据目录由 Android 管理。

## 构建并运行测试

需要 Windows、PowerShell 7、JDK 17、`curl.exe`、Android SDK `platforms/android-36` 和 `build-tools/36.0.0`。首次构建会从 Maven Central 下载 Vosk 和 JNA，从 Alpha Cephei 下载普通话模型，并核对 SHA-256。 [Maven Central](https://repo.maven.apache.org/maven2/) · [Vosk 模型](https://alphacephei.com/vosk/models)

在项目根目录运行以下命令。

```powershell
pwsh -NoProfile -File .\tools\build.ps1 -Test
```

脚本默认检测 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 或本机标准 SDK 路径。必要时用 `-Sdk` 传入 SDK 目录。构建包括 Java 测试、Android 资源打包、DEX 编译和 APK 签名核验。APK 按版本号输出到 `artifacts/`，当前为 `artifacts/SelfControl-0.1.2.apk`。

## 实现范围

首版使用原生 Java 界面和 Android SQLite，在当前 SDK、JDK 上即可从源码构建。开源调研对照了 SelfLock `57ea0a89cf1749e4b37f3232a4819c0fe61bbf17` 的用时仓储和无障碍实现。该仓储绑定其已有数据库模型，拦截服务还包含应用界面节点读取。这里实现了本项目独立的计时与无障碍覆盖层。 [SelfLock 源码](https://github.com/EtashTyagi/SelfLock/tree/57ea0a89cf1749e4b37f3232a4819c0fe61bbf17)

朗读功能随 APK 提供 Vosk 普通话离线模型。第一次打开朗读页时，软件将模型解压到 Android 应用私有目录。录音仅在按住时进行，转写和匹配都在设备上完成。麦克风不可用或识别未通过时，可以直接切到打字或算术题。依赖与模型的许可随 APK 提供。 [Vosk 普通话模型](https://alphacephei.com/vosk/models) · [JNA 许可](https://github.com/java-native-access/jna/blob/5.18.1/LICENSE)

分屏、画中画及无法确认前台归属时暂停拦截。服务刚连接且窗口归属未知时，等到下一次可识别的窗口事件再恢复。DeepSeek 日程与番茄钟排在第二阶段。
