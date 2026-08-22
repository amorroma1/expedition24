# 安装 MFD-24

[English](../INSTALL.md) · [Français](INSTALL.fr.md) · [Deutsch](INSTALL.de.md) · [Italiano](INSTALL.it.md) · [日本語](INSTALL.ja.md) · **中文**

MFD-24 只有一种分发方式:[GitHub 发布页](https://github.com/amorroma1/expedition24/releases/latest)上带签名的 `app-earth-release.apk`。Wear OS 没有侧载界面,所以下面每条路线最终都落在 ADB 上 —— 唯一的问题是由哪台机器来运行它。三条路线,从最简单的开始。全部都需要先打开[开发者选项](#第一步手表上的开发者选项),从那里开始。

需要 Wear OS 3.0 或更新(API 30)。在 TicWatch Pro 3 Ultra(454 × 454)上开发并佩戴;布局按半径等比缩放,其他圆形屏幕应该也没问题。

## 为什么 MFD-24 不上架 Google Play

这是一个决定,不是待办事项。三个理由:

- **失能报警监视器不应该被冲动安装。** 值守监视功能是未经认证的辅助手段 —— 它恰恰只对那些在信任它之前会先读清楚它做什么、不承诺什么的人有用。商店页面生来就是让人三十秒内点下去的;而侧载是被阅读、被校验、有意为之的安装,面向的正是这块表盘为之打造的人群。
- **所需权限属于"昂贵"的那一类。** 后台定位、身体传感器和健康类前台服务在这里都名正言顺 —— 天气任务在后台运行,监视器在熄屏时读取加速度计 —— 但在 Play 上,它们会把一个业余项目推进与商业健康应用相同的常驻审查机器:政策年年变,沉默的默认结局是下架。这些时间花在表盘上更值。
- **你可以验证你安装的东西。** 每个发布的说明里都印着 APK 的 SHA-256,APK 自 1.0.0 起用同一把密钥签名,生成它的源码只隔一个标签。商店只会增加一个中间人,而不是保证。

这些都与许可证无关 —— GPL 软件在 Play 上是允许的。关键在于:值更仪表由谁安装、带着多少斟酌去安装。

## 第一步:手表上的开发者选项

1. 在手表上:**设置 → 系统 → 关于 → 版本**(措辞因厂商而异),连点 **版本号** 七次,直到提示你已成为开发者。
2. 回到设置,打开**开发者选项**,开启 **ADB 调试**和**无线调试**(Wear OS 3 上可能叫 **通过 Wi-Fi 调试**)。
3. 让手表与将要执行安装的手机或电脑处于**同一个 Wi-Fi 网络**。

## 路线 1 —— 只用一部手机:Wear Installer 2

最平缓的路线:一款免费的 Android 手机应用,替你完成 ADB 握手,并把每一步显示在屏幕上。它是第三方免费软件(Wear Installer 2,作者 Malcolm Bryant / freepoc)—— 不属于本项目,但正是干这件事的常用工具。

1. 在**手机**上从 Google Play 安装 **Wear Installer 2**。
2. 在**手机**上从[最新发布](https://github.com/amorroma1/expedition24/releases/latest)下载 `app-earth-release.apk`。
3. 在 Wear Installer 2 中跟随向导:它会询问手表的 IP 地址和配对码 —— 两者都在手表的**开发者选项 → 无线调试 → 配对新设备**里。
4. 指向下载好的 APK,让它完成安装。
5. 在手表上:长按当前表盘,滑到 **MFD-24**,点它。

## 路线 2 —— 一台装有 ADB 的电脑

正统路线,其余一切不过是它的糖衣。

1. 获取 [Android platform-tools](https://developer.android.com/tools/releases/platform-tools)(一个小 zip,`adb` 就在里面),并从[最新发布](https://github.com/amorroma1/expedition24/releases/latest)下载 `app-earth-release.apk`。
2. *(这十秒钟值得)* 用发布说明中印着的 SHA-256 校验下载文件:Windows 上 `certutil -hashfile app-earth-release.apk SHA256`,macOS/Linux 上 `shasum -a 256 app-earth-release.apk`。
3. 在手表上:**开发者选项 → 无线调试 → 配对新设备**。它会显示 IP、**配对端口**和六位配对码。趁该对话框还开着:

   ```
   adb pair 192.168.1.50:37000 123456
   ```

4. 回到无线调试主界面,手表显示第二个、**不同的**端口 —— 连接端口:

   ```
   adb connect 192.168.1.50:41234
   adb install -r app-earth-release.apk
   ```

5. 把它设为当前表盘 —— 在手表自己的表盘选择器里选,或者:

   ```
   adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
       --es operation set-watchface \
       --ecn component com.avdesign.mfd24/com.avdesign.mfd24.MfdWatchFaceService
   ```

会出什么问题(一定会出):

| 症状 | 原因与对策 |
|---|---|
| `adb connect` 失败或卡住 | 你给的是**配对**端口。连接端口是无线调试主界面上的另一个数字。 |
| `protocol fault (couldn't read status message)` | 配对码随对话框一起过期了。重新打开**配对新设备**,趁它显示时运行 `adb pair`。 |
| 命令中途 `error: closed` 或 `device offline` | 屏幕休眠时手表掉出了 Wi-Fi。唤醒屏幕后重连 —— 如果无线调试被重新开关过,准备好面对一个**新端口**。 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 已安装的构建与你正要安装的签名密钥不同(自编译 APK 覆盖发布版,或反过来)。先卸载 —— 设置也会随之消失。 |

## 路线 3 —— 从源码构建

为了阅读、修改,或者除了自己的二进制谁都不信。

```
git clone https://github.com/amorroma1/expedition24.git
cd expedition24
./gradlew :app:assembleEarthDebug
adb install -r app/build/outputs/apk/earth/debug/app-earth-debug.apk
```

需要 JDK 17+(把 `JAVA_HOME` 指向 Android Studio 自带的即可)和 Android SDK。`assembleEarthRelease` 构建发布变体;未配置签名密钥时会回落到调试密钥库,APK 仍能装到你自己的手表上。

**签名就是边界:** 自编译的 APK 和 GitHub 发布版无法互相覆盖安装,因为 Android 要求更新共享同一把签名密钥。跨越意味着先卸载 —— 设置、值更状态和事件日志会随卸载一起消失。选一条道 —— 佩戴用发布版,折腾用自己的构建 —— 然后待在那条道上。

## 更新

有新发布时手表会告诉你。它不会安装,也不假装能安装。

- **每天一次、非值更时,向 GitHub 询问是否有更新的版本。** 只读一小段 JSON。**永不下载任何东西。** 值更期间不运行,并可在 **ABOUT → RELEASE CHECK** 中彻底关闭。
- **每个发布只通知一次**,等待中的版本由 **ABOUT → RELEASES** 标明。
- **点它会把发布页面显示为二维码。** 用手机对准即可:更新说明、SHA-256 和 APK 都在那一页上,在浏览器里,字号可读。
- **然后照常从电脑安装:**

  ```
  adb install -r app-earth-release.apk
  ```

**为什么不能从手表装?** 因为 Wear OS 不允许:会话提交后,平台请求确认,而它自带的安装器回答 *「Install/Uninstall actions not supported on Wear」* —— 在 API 30 模拟器和运行 Wear 3.5 的 TicWatch Pro 3 Ultra 上均已验证。所有绕路对普通应用都是关闭的。

## 安装之后

- **设置**藏在长按表盘、再点铅笔之后。表盘需要的一切都在那里索取 —— 天气、Nadir 和站点锁定所需的定位;在选择需要权限的读数插槽的那一刻索取传感器权限。安装时什么都不要。完整的权限表在 [README](../../README.md#permissions)。
- **更新**会自己找上门 —— 见上文[更新](#更新);用新发布版跑 `adb install -r` 也永远可行。两种方式设置都会保留,除非该发布改动了设置模式,而那种情况发布说明会明说。
- **没有伴侣应用,没有账号。** APK 就是产品的全部。
