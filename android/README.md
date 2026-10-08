# Collie Pocket 安卓客户端

这是基于 [AltanS/collie](https://github.com/AltanS/collie) 的非官方 Android 客户端，保留原项目 MIT 许可。电脑继续运行 Collie 和 herdr；手机安装此 APK，通过 Tailscale 连接电脑。

第一版采用 Android WebView 承载 Collie 原有界面，并增加原生连接设置、配对、文件选择和返回键处理。没有浏览器地址栏；页面功能和布局仍来自电脑提供的 Collie。此版没有后台推送、麦克风录音或后台连接保活，电脑断网、休眠或关闭服务后不能远程操作。

## 先在电脑看效果

已经配置好环境的电脑，双击本目录的 **Start-Preview.cmd**。它会打开 `Collie_Preview` 虚拟手机，把 `build/collie-pocket-0.1.0.apk` 安装进去并启动。首次开机可能需要几分钟。关闭虚拟手机窗口不影响电脑上的 herdr。

新电脑需要先安装 Android SDK、Android Emulator 和 `system-images;android-35;google_apis;x86_64`，在 Device Manager 创建名为 `Collie_Preview` 的 Android 15 虚拟设备，并设置 `ANDROID_HOME`。启动脚本使用电脑的 Tailscale DNS；电脑应先登录 Tailscale。

## 安装到真手机

1. 保持手机 Tailscale 已连接，与电脑使用同一账号或已授权的同一 tailnet。
2. 将构建出的 APK 传到手机并点击安装。如果系统询问，允许下载 APK 的应用安装此文件。
3. 打开 **Collie Pocket**。点右上角 `⋮ → 连接设置`，填电脑现有的 Collie 首页地址，包括 `http://` 和端口；个人构建可以预填地址。
4. 在电脑双击本目录的 **Pair-Phone.cmd**（或运行 `collie pair`），得到一个有时限、只能用一次的 8 位配对码。
5. 在 App 点 `⋮ → 配对这台设备`，输入配对码，为设备取个名字，例如 `my-phone-app`。配对成功后可以操作。

App 与 Chrome 的配对记录独立。重启 App 和覆盖安装同签名的新 APK 会保留记录；清除 App 数据、卸载后重装或更换服务器地址需要重新配对。失去手机时，在电脑用 `collie devices` 查看帮助并撤销对应设备。

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
```

测试会构建一个单独的 instrumentation 测试 APK，重启主 App 并检查配对凭据是否保留、未配对的写请求是否被拒绝，以及已配对请求是否通过鉴权。所有写请求仅指向明确不存在的测试 pane，不向实际 agent 发送输入。测试 APK 不包含在交付的主 APK 中，也不读取或打印配对令牌。

## 安全边界

HTTP 地址仅接受本地、局域网和 Tailscale 主机；公网地址须使用 HTTPS。HTTPS 证书错误会被拒绝。配对请求仍经过 Collie 的 Origin、Tailscale 身份和一次性配对码检查；本客户端没有添加绕过鉴权的接口。配对令牌仅写入对应 Collie 来源的 App 私有 WebView 存储，不交给外部链接。外部网页使用 Google Chrome 打开。应用不申请相机、麦克风或整个存储的权限，文件由系统选择器授权。

工具参考：[Android WebView](https://developer.android.com/develop/ui/views/layout/webapps/webview)、[Android Emulator](https://developer.android.com/studio/run/emulator)、[AGP 8.9 兼容性](https://developer.android.com/build/releases/agp-8-9-0-release-notes)。
