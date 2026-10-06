# 河马小手（河马剧场 Xposed 模块）

基于 Modern Xposed API 102 的 Android 模块，用于去除河马剧场广告并解锁清晰度切换菜单。

| 项 | 值 |
| --- | --- |
| 模块名称 | 河马小手 |
| 模块包名 | `com.dz.hmxs` |
| 入口类 | `com.dz.hmxs.HippoXposedModule` |
| 目标应用 | 河马剧场 `com.dz.hmjc`（静态作用域） |
| 偏好文件 | `hippo_xposed_config` |

## 功能

- **去除广告**
  - 开屏（启动页）广告：让 `AdManager.v()` 判定为"不满足加载条件"，走 App 自身的无广告分支（不阻塞开屏、不卡等待）
  - 信息流 / 视频前贴片：`AdManager.l`、`AdManager.A`、`AdManager.B`
  - 阅读页广告：`ReaderAdManager.a`、`ReaderAdManager.e`、`ReaderAdManager.h`
  - 剧集解锁激励广告与插屏：解锁加载器 + `UnlockAdVM`
- **解锁清晰度切换菜单**：强制打开两个开关（服务端开关 `ResolutionRateConfig.getResolutionRateSwitch()`、本地开关 `data.b.z5()`），打开后可在菜单里自由选择服务端已下发的各档画质。
- **注入自检**：每次注入核对全部目标类/方法，`[OK]`/`[MISS]` 逐项列出；首次注入额外弹一次屏幕提示。

设置页可分别开关：去除广告、解锁视频清晰度、屏蔽阅读页广告、屏蔽视频解锁广告（无桌面图标，用 adb 打开，见下）。

## 关于 1080P（重要）

**模块不解锁 1080P，也做不到**，这是实测结论而非未实现：

- 客户端确实把 `resolutionRate=1080P` 发给了服务端（抓包确认），服务端仍按账号权限只下发 **720P** 直链；
- 内容接口的 `resolutionRates` 里 1080P 标记 `needVip:1`，但 CDN 上不存在对应文件——用同一份**有效签名**做对照实验：`720p.narrowv3 → 200`，而 `1080p.narrowv1/v4`、无 profile 的 `1080p`、`1080p.h265` 等命名全部 **404**（404 而非 403，说明签名不拦路径，纯粹是文件不存在）；
- 请求体是明文 JSON，里面**没有任何 VIP 身份字段**可伪造，VIP 由服务端按账号 token 判定；
- 因此只能做到"解锁切换菜单"，能自由选 540P / 720P，**画质上限由服务端控制**。要 1080P 只能服务端认账（例如真实会员账号）。

## 支持版本

- 目标应用：河马剧场，包名 `com.dz.hmjc`，适配版本 `3.11.1`（versionCode `11031101`）。
- Android 5.0（API 21）及以上。
- Modern Xposed API 102，需要支持 API 102 的框架（如新版 LSPosed / Zygisk-LSPosed）。
- 该版本启用了 R8 混淆：代码里的业务类名/方法签名都是对照 `apk_out/` 反编译产物逐个核对过的，**升级 App 后必须重新对照 smali 再改**。

## 构建

在项目根目录执行（Gradle Wrapper，会自动下载 Gradle 8.2）：

```powershell
# Windows
.\gradlew.bat :XposedModule:assembleDebug

# Linux / macOS
./gradlew :XposedModule:assembleDebug
```

Debug APK 已使用 Android 调试证书签名，可直接安装：

`XposedModule/build/outputs/apk/debug/XposedModule-debug.apk`

生成未签名 Release APK：

```powershell
.\gradlew.bat :XposedModule:assembleRelease
```

Release 输出：

`XposedModule/build/outputs/apk/release/XposedModule-release-unsigned.apk`

首次构建需要 Android SDK。请自行创建 `local.properties` 指定 SDK 路径（该文件不入库）：

```properties
sdk.dir=C:/Users/<你的用户名>/AppData/Local/Android/Sdk
```

也可以用 Android Studio 直接打开本目录构建。

> 本仓库**不包含**河马剧场的原始 APK 与反编译产物（`apk_out/`），
> 因为那属于第三方版权内容。想按本文档适配新版本，请自行对目标 APK 反编译。

## 安装与启用

1. 安装 Debug APK。应用名是 **河马小手**，但**不注册桌面入口**——桌面上和应用列表里都不会出现图标。
2. 在 LSPosed 里启用该模块并勾选作用域 `河马剧场`。**启用后即生效**，不需要打开任何界面（四个开关默认全开，配置项不必手动初始化）。
3. **注意**：LSPosed 按包名记录模块启用状态，改动包名（例如从 `com.dz.hippo.xposed` 改成 `com.dz.hmxs`）会被视为全新模块，需要重新启用一次。
4. 强制停止河马剧场后重新打开，模块在注入时会先做一次**自检**（见下）。

### 注入自检

每次注入都会在 logcat 打出目标类/方法的核对结果（过滤 `HippoXposed`）：

```text
========== 河马小手 注入自检 ==========
目标应用 : com.dz.hmjc
当前进程 : com.dz.hmjc
框架     : LSPosed 2.2.1 (API 102)
[OK]   广告: AdManager.l (拦截加载)  <- com.dz.platform.ad.a#l
...
[OK]   清晰度: data.b.z5  <- com.dz.business.base.data.b#z5
结果     : 13/13 项全部匹配，模块可正常工作
======================================
目标应用版本: 3.11.1 (11031101)
```

- `[OK]` = 目标类与方法都在；`[MISS]` = 对不上，末尾会汇总缺失项并提示对照 smali 适配。
- App 升级后 R8 会重新混淆，靠这一段就能立刻定位是哪个 hook 失效了，不用等它静默不生效。
- **首次**注入（主进程）还会在屏幕上弹一次 Toast 汇总结果，之后不再弹。
  记「已提示过」用的是目标应用私有目录下的空标记文件
  `/data/data/com.dz.hmjc/files/.hmxs_selfcheck_shown`，删掉它即可让提示重新出现。

### 打开设置界面

因为不注册桌面入口，需要时用 adb 打开：

```powershell
adb shell am start -n com.dz.hmxs/.SettingsActivity
# 或者
adb shell am start -a com.dz.hmxs.action.SETTINGS
```

> 注意：LSPosed 通过 `getRemotePreferences` 暴露给注入进程的偏好是**只读**的
> （写入会抛 `UnsupportedOperationException: Read only implementation`），
> 所以配置只能由模块自己的设置界面写入，注入端只读。`ModuleConfig` 的写入路径已经吞掉该异常，
> 避免打断 `onPackageReady`。

## 配置

设置界面使用模块偏好文件 `hippo_xposed_config`。模块通过 Modern API 的远程偏好读取同一组配置，目标应用进程不会直接读取模块的私有文件。

## 实现

### 广告去除

| Hook | 作用 |
| --- | --- |
| `com.dz.platform.ad.a` → `l(...)`（static synthetic，receiver 是第一个实参，共 30 参） | 拦截广告加载 |
| `com.dz.platform.ad.a` → `A(int,String,int,int,String,String,Boolean)` | 拦截广告展示 |
| `com.dz.platform.ad.a` → `B(int,String,String,Boolean)` | 拦截广告展示 |
| `com.dz.platform.ad.a` → `v(SplashAdVo,boolean,boolean)` 返回 false | 开屏广告走"不需要展示"分支 |
| `com.dz.business.reader.ad.a` → `a/e/h` | 阅读页广告 |
| `UnlockRewardAdLoader.G(...)` / `InterstitialLoader.t(...)` | 解锁激励/插屏：跳过展示并回调成功 |
| `UnlockAdVM.d0(boolean,String)` / `onClose(boolean)` | 强制解锁完成 |

> 参数里的 `Boolean` 是装箱类型（`java.lang.Boolean`），不是 `boolean.class`，写错会 `NoSuchMethodException`。

### 清晰度解锁

- `ResolutionRateConfig.getResolutionRateSwitch()` → 强制 `true`
- `com.dz.business.base.data.b.z5()` → 强制 `true`

> 旧版按 `BasePlayer.setOption(VIDEO_BITRATE)` 覆盖码率的做法在 3.11.1 **不可行**：dz 的播放器（混淆为 `com.dz.platform.player.player.k`）只有读取方法 `y(Option)F`，没有写 bitrate 的 `setOption`，App 也不调用 `setDefaultResolution`；`AliPlayer` 是接口，无法这样 hook。

## 目录结构

```text
XposedModule/
├── build.gradle.kts
├── proguard-rules.pro
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/dz/hmxs/
│   │   ├── HippoXposedModule.kt
│   │   ├── ModuleConfig.kt
│   │   └── SettingsActivity.kt
│   ├── resources/META-INF/xposed/
│   │   ├── java_init.list        # 入口类 com.dz.hmxs.HippoXposedModule
│   │   ├── module.prop           # minApiVersion/targetApiVersion = 102
│   │   └── scope.list            # com.dz.hmjc
│   └── res/
└── build/outputs/apk/
```

Modern API 不再使用 `assets/xposed_init` 或清单中的旧 Xposed 元数据；入口、模块配置和作用域分别由 `META-INF/xposed` 文件提供。

## 注意事项

1. 仅供学习研究使用，请遵守相关法律法规和应用服务条款。
2. 使用前请备份重要数据。
3. 目标应用更新后，广告与播放器方法可能变化，需要重新适配 Hook。
4. 本项目不对使用模块造成的任何问题负责。

## 许可

MIT License
