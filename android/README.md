# Collie Pocket 安卓客户端

这是基于 [AltanS/collie](https://github.com/AltanS/collie) 的非官方 Android 客户端，保留原项目 MIT 许可。电脑继续运行 Collie 和 herdr；手机安装此 APK，通过 Tailscale 连接电脑。

采用 Android WebView 承载 Collie 原有界面，并增加原生多电脑配置、配对、文件选择和返回键处理。没有浏览器地址栏、常驻 App 顶部栏或底部连接卡片；电脑切换与配对集中在原有右上角齿轮打开的设置页，通过「电脑连接」进入。页面功能和布局仍来自电脑提供的 Collie。此版没有后台推送、麦克风录音或后台连接保活，电脑断网、休眠或关闭服务后不能远程操作。

## 先在电脑看效果

已经配置好环境的电脑，双击本目录的 **Start-Preview.cmd**。它会打开 `Collie_Preview` 虚拟手机，把 `build/collie-pocket-0.1.5.apk` 安装进去并启动。首次开机可能需要几分钟。关闭虚拟手机窗口不影响电脑上的 herdr。

新电脑需要先安装 Android SDK、Android Emulator 和 `system-images;android-35;google_apis;x86_64`，在 Device Manager 创建名为 `Collie_Preview` 的 Android 15 虚拟设备，并设置 `ANDROID_HOME`。启动脚本使用电脑的 Tailscale DNS；电脑应先登录 Tailscale。

## 安装到真手机

1. 先安装并登录手机 Tailscale，与电脑使用同一账号或已授权的同一 tailnet，并至少手动允许一次 VPN 连接。
2. 将构建出的 APK 传到手机并点击安装。如果系统询问，允许下载 APK 的应用安装此文件。
3. 打开 **Collie Pocket**。首次无地址时点「连接设置」添加电脑；已有连接时点右上角齿轮 →「电脑连接」→「切换 / 管理电脑」→「添加电脑」，填写名称与 Collie 首页地址，包括 `http://` 和端口；个人构建可以预填地址。
4. 在电脑双击本目录的 **Pair-Phone.cmd**（或运行 `collie pair`），得到一个有时限、只能用一次的 8 位配对码。
5. 在 App 点 `右上角齿轮 → 电脑连接 → 配对这台设备`，输入配对码，为设备取个名字，例如 `my-phone-app`。配对成功后可以操作。

App 与 Chrome 的配对记录独立。直接安装 0.1.5 覆盖旧版，无需先卸载；现有电脑配置、配对和终端/聊天视图设置保留。每台电脑首次连接需单独配对，切换回已配对的原地址无需再次配对。清除 App 数据、卸载后重装或改用另一地址需要重新配对。失去手机时，在各台电脑用 `collie devices` 查看帮助并撤销对应设备。

## 添加 Windows 与 Mac

1. 在两台电脑上分别运行 Collie 与 herdr，并让手机和电脑加入同一 Tailscale 网络。
2. 点右上角齿轮 →「电脑连接」→「切换 / 管理电脑」→「添加电脑」，分别保存 Windows 和 Mac 的名称与地址。
3. 切换到新电脑后，点右上角齿轮 →「电脑连接」→「配对这台设备」，使用那台电脑生成的一次性配对码。
4. 以后从「切换 / 管理电脑」选择电脑；「编辑当前电脑」可以重命名、修改地址或移除。

连接失败时仍可打开电脑列表，切换回在线的电脑。切换会关闭旧页面及其返回历史，每个服务器来源的配对和视图设置分别保留；删除列表条目不会停止电脑服务或撤销配对授权。同一服务器来源不能重复添加。

Space 是 herdr 的工作区。单独创建 Space 不会连接另一台电脑；要在同一 Collie 主页汇总多台电脑的 Space，可另行配置上游的 [Crew 模式](../docs/crew.md)。APK 的电脑列表直接切换各自的 Collie 服务，不需要 Crew。

查看会话时没有额外 App 栏。用会话自己的返回按钮或安卓返回手势回到主页，点右上角齿轮 →「电脑连接」就能修改连接、重新配对、刷新主页、打开 Tailscale 或查看开源许可。原有外观、设备、通知和系统设置保持原来的入口。加载失败时，错误页仍有重试和连接设置按钮，不依赖远端设置页。

## 自动连接 Tailscale

0.1.5 默认开启「打开 App 时自动连接 Tailscale」，可在右上角齿轮 →「电脑连接」→「Tailscale 自动连接」关闭或手动连接；离线错误页也有「Tailscale 设置」入口。

每次 App 回到前台、切换电脑或点重试时，仅对 `.ts.net`、`100.64.0.0/10` 或 Tailscale IPv6 地址先读取 `/api/health`。已能访问电脑就直接继续；否则先请求静默连接。如果几秒后仍不可访问，会自动打开 Tailscale，等待约 3 秒初始化后再发送一次连接请求；此时可能看到 Tailscale 界面，连接后按返回键回到 App 即可继续加载。一分钟内最多自动打开一次，避免电脑关机或网络故障时来回跳转。普通等待最多约 20 秒，局域网 / 公网地址不自动启动 Tailscale，退出 App 不断开 VPN。

0.1.4 的单次广播在用户手机上不能可靠冷启动 Tailscale；0.1.5 加入前台唤醒兜底。接口不返回连接成功结果，因此以电脑实际可访问为准。需事先安装、登录并授权 Tailscale；若系统撤销了 VPN 授权、另一个 VPN 占用连接或手机限制后台启动，打开 Tailscale 手动确认后返回 App。Android 同一用户一次只允许一个活动 VPN。本功能不申请 VPN 服务权限，也不更改 Tailscale 账号、退出节点或电脑端配置。

启动兼容性依据：[Tailscale 冷启动 / 广播可靠性记录](https://github.com/tailscale/tailscale/issues/18847)。

实现依据：[官方 IPNReceiver](https://github.com/tailscale/tailscale-android/blob/main/android/src/main/java/com/tailscale/ipn/IPNReceiver.java)、[StartVPNWorker](https://github.com/tailscale/tailscale-android/blob/main/android/src/main/java/com/tailscale/ipn/StartVPNWorker.java)。

## 第一次安卓开发需要知道什么

| 名称 | 在这里做什么 |
| --- | --- |
| 源码 | `app/src/main/java/` 里的 Java 文件决定 App 行为 |
| Android SDK | 编译和安装安卓应用的工具，平台版本是 API 35 |
| JDK | 编译 Java，使用 JDK 17 或更新版本 |
| 模拟器 / AVD | 在 Windows 上运行的虚拟安卓手机，用于先试效果 |
| APK | 最终安装到真手机或模拟器的文件 |
| 签名 | 安卓识别应用更新的身份；后续更新必须保留同一个签名密钥 |

日常顺序是：**修改源码 → 构建 APK → 在模拟器试用 → 再装到手机**。

在 PowerShell 中进入本目录：

```powershell
# 构建通用版，首次打开手动填写服务器地址
.\Build-Android.ps1

# 构建个人版，仅在本机 APK 内预填自己的地址，不改公开源码
.\Build-Android.ps1 -Server 'http://your-computer.your-tailnet.ts.net:8787/'

# 个人构建预填多台电脑；地址和名称仅进入生成的 APK
.\Build-Android.ps1 -Server 'http://windows.your-tailnet.ts.net:8787/' -Computer @('Windows=http://windows.your-tailnet.ts.net:8787/', 'Mac=https://mac.your-tailnet.ts.net/')

# 运行地址验证测试
.\Test-Address.ps1

# 安装并打开虚拟手机预览
.\Start-Preview.ps1
```

构建脚本需要 Python 3，仅使用标准库；使用官方 SDK 的 aapt2、javac、D8、zipalign 和 apksigner 生成并验证 APK。默认从 `%LOCALAPPDATA%\collie-android-tools\sdk` 找 SDK，也可以设置 `ANDROID_HOME`。JDK 优先使用 `JAVA_HOME`，否则查找本机 Android Studio 或已安装的 PyCharm JBR。

个人签名保存在 `%LOCALAPPDATA%\collie-pocket\signing`，不会进入 Git。随机密码通过 Windows DPAPI 加密，仅当前 Windows 用户能解密；目录只允许当前用户、SYSTEM 和管理员访问。需要迁移电脑前请妥善备份签名密钥及恢复方式，勿删除此目录或将其发给别人。

## 使用 Android Studio

本目录也提供标准 Gradle 工程（AGP 8.9.2 / Gradle 8.11.1）。Android Studio 中选择 **Open**，打开 `android` 目录，等待 Gradle 同步，选择虚拟设备后点运行。首次同步需要下载依赖。

```powershell
.\gradlew.bat :app:assembleDebug
```

Android Studio 的默认 debug 签名与 `Build-Android.ps1` 的个人签名不同，不能互相覆盖安装。学习时可以使用另一台虚拟设备；如果卸载后换装，配对记录也会删除。用于真手机长期更新时，统一使用 `Build-Android.ps1` 的个人签名。

## 在已配对的模拟器上验证鉴权

先使用 `Build-Android.ps1` 安装个人签名版并完成配对，设置 `ANDROID_HOME` 和 `JAVA_HOME` 后运行：

```powershell
python test_device.py --serial emulator-5554

# 自动连接生命周期检查：不要求配对，不切换真实 VPN
python test_device.py --serial emulator-5554 --connection-only

# 实际跨 App 唤醒检查：仅限没有安装真实 Tailscale 的测试模拟器
python test_device.py --serial emulator-5554 --wake-only
```

测试会构建一个单独的 instrumentation 测试 APK，重启主 App 并检查配对凭据是否保留、未配对的写请求是否被拒绝，以及已配对请求是否通过鉴权；还会从齿轮进入设置页，检查「电脑连接」能通过真实点击打开原生电脑管理、脚本点击不会触发原生控制、原有设置仍可见，以及主页和会话没有额外 App 栏或连接底栏。多电脑测试检查迁移、增删改、重复地址验证和选择持久化，再通过模拟器内部两个临时 HTTP 来源验证 WebView 存储隔离、切换后清除返回历史、回到原电脑仍有配对。所有写请求仅指向明确不存在的测试 pane，不向实际 agent 发送输入。自动连接专项检查另覆盖地址识别、只读探测、不重复连接、恢复后继续、缺少 Tailscale、进入后台取消和超时；用隔离后端模拟 VPN 结果。`--wake-only` 在没有真实 Tailscale 的模拟器临时安装一个明确标注的独立测试 App，验证接收器冷启动、打开应用、初始化后延迟发送连接请求，以及返回后不重复跳转；结束后自动卸载测试 App，不覆盖真实 Tailscale、不使用任何登录凭据。测试不冒充真手机的 VPN 已连接，实际 Tailscale 与厂商系统仍需真机确认。测试 APK 不包含在交付的主 APK 中，也不导出或打印配对令牌。

## 安全边界

HTTP 地址仅接受本地、局域网和 Tailscale 主机；公网地址须使用 HTTPS。HTTPS 证书错误会被拒绝。配对请求仍经过 Collie 的 Origin、Tailscale 身份和一次性配对码检查；本客户端没有添加绕过鉴权的接口。配对令牌仅写入对应 Collie 来源的 App 私有 WebView 存储，不交给外部链接。「电脑连接」条目由 APK 内的适配脚本添加到设置页，仅在已配置的服务器来源执行；原生操作要求主页面上的用户点击，不添加 `JavascriptInterface`。外部网页使用 Google Chrome 打开。应用不申请相机、麦克风或整个存储的权限，文件由系统选择器授权。

工具参考：[Android WebView](https://developer.android.com/develop/ui/views/layout/webapps/webview)、[Android Emulator](https://developer.android.com/studio/run/emulator)、[AGP 8.9 兼容性](https://developer.android.com/build/releases/agp-8-9-0-release-notes)。
