# 河马小手（河马剧场 Xposed 模块）

基于 Modern Xposed API 102 的 Android 模块，用于去除河马剧场广告。

| 项 | 值 |
| --- | --- |
| 模块名称 | 河马小手 |
| 模块包名 | `com.dz.hmxs` |
| 入口类 | `com.dz.hmxs.HippoXposedModule` |
| 目标应用 | 河马剧场 `com.dz.hmjc`（静态作用域） |
| 偏好文件 | `hippo_xposed_config` |

## 下载

编译好的 APK 在 **Releases** 页：

**➡️ [下载最新版 HemaXiaoShou-v1.0.2.apk](https://github.com/xiaopi0329/HemaXiaoShou/releases/latest)**

| 项 | 值 |
| --- | --- |
| 文件名 | `HemaXiaoShou-v1.0.2.apk` |
| 大小 | **50,666 字节（49 KB）** |
| SHA-256 | `7D147D1465C323E510FB5464A49A1A5013F2D280C34071470B47B9B452987D91` |
| 签名 | Android debug key |

APK 不入源码库（`.gitignore` 排除 `*.apk`），只作为 Release 附件分发。也可以自行构建，见下方「构建」。

## 版本变更

### v1.0.2 — 体积从 4.81 MB 降到 49 KB

两步压缩，**减少 99%**：

1. **开启 R8（`isMinifyEnabled` + `shrinkResources`）**：dex 从 8.9 MB 降到 1.6 MB —— 5,048,554 → 2,275,502 字节
2. **移除整个设置界面**：设置页正是 `appcompat` / `material` / `preference` 三个库存在的唯一理由，
   连同 `themes.xml`、`colors.xml` 一起删除后，4 个 androidx 依赖全部砍掉，`resources.arsc`（原 1 MB）整个消失 —— 2,275,502 → **50,666 字节**

其他改动：

- `ModuleConfig` 简化为只读：删除无调用方的 `init()` 与写入路径
  （LSPosed 远程偏好本就是只读的，写入会抛 `UnsupportedOperationException`）
- 三个功能开关保留为固定默认值 `true`，**没有配置界面了**，全部功能默认开启
- 自检与 11 个 hook 不受影响（真机复验 11/11）

体积演进：

| 版本 | 构成 | 大小 |
| --- | --- | --- |
| v1.0.1 | 未压缩 + 有设置页 | 5,048,554 字节 |
| v1.0.2 | R8 压缩 + 有设置页 | 2,275,502 字节 |
| **v1.0.2** | **R8 压缩 + 无设置页** | **50,666 字节** |

### v1.0.1

- **移除「解锁视频清晰度」功能**：该功能经复核不提升画质，只影响清晰度菜单能否打开
  （开关关 = App 强制 720P；开关开 = 最多也只能选 720P，上限完全相同），属于名不副实的声明，故整体删除
- 删除 `hookQualitySwitch()` 及 `ResolutionRateConfig` / `data.b.z5` 两个 hook，hook 总数 13 → **11**
- 设置页移除「解锁视频清晰度」开关，应用描述改为「去除河马剧场广告」
- 自检清单同步更新为 11 项

### v1.0.0

- 首个版本：去广告（开屏 / 信息流 / 前贴片 / 阅读页 / 剧集解锁）、注入自检
- 不注册桌面入口，LSPosed 启用即注入

## 功能

- **去除广告**
  - 开屏（启动页）广告：让 `AdManager.v()` 判定为"不满足加载条件"，走 App 自身的无广告分支（不阻塞开屏、不卡等待）
  - 信息流 / 视频前贴片：`AdManager.l`、`AdManager.A`、`AdManager.B`
  - 阅读页广告：`ReaderAdManager.a`、`ReaderAdManager.e`、`ReaderAdManager.h`
  - 剧集解锁激励广告与插屏：解锁加载器 + `UnlockAdVM`
- **注入自检**：每次注入核对全部目标类/方法，`[OK]`/`[MISS]` 逐项列出；首次注入额外弹一次屏幕提示。

> **本模块不提供画质解锁功能** —— 做不到，原因见下节「关于画质」。

模块**无任何界面**（无桌面图标、无设置页），上述功能安装后始终开启，见「配置」。

## 关于画质（重要）

**本模块不提供"画质解锁"，因为它做不到 —— 这不是没实现，而是由服务端决定。**

### 免费账号的画质上限就是 720P

- 服务端下发的 `resolutionRates` 为：`1080P (needVip:1)`、`720P (needVip:0)`、`540P (needVip:0)`；
- 客户端确实把 `resolutionRate=1080P` 发给了服务端（抓包确认请求体），服务端仍只下发 **720P** 直链；
- 内容接口的 1080P 标记为 `needVip:1`，但 CDN 上不存在对应文件——用同一份**有效签名**做对照实验：
  `720p.narrowv3 → 200`，而 `1080p.narrowv1/v4`、无 profile 的 `1080p`、`1080p.h265` 等命名全部 **404**
  （404 而非 403，说明签名不拦路径，纯粹是文件不存在）；
- 请求体是明文 JSON，里面**没有任何 VIP 身份字段**可伪造，VIP 由服务端按账号 token 判定。

### 那两个清晰度开关为什么也提升不了画质

模块确实 hook 了 `ResolutionRateConfig.getResolutionRateSwitch()` 与 `com.dz.business.base.data.b.z5()`
并强制返回 `true`（早期版本把它当作"解锁清晰度"）。但复核后结论是**它不构成功能**：

| 服务端开关 | App 行为 | 你能拿到的最高画质 |
| --- | --- | --- |
| 关 | App 自己强制 720P（`Uf("720P")`） | 720P |
| 开 | 清晰度菜单可用，最多能选到 720P | 720P |

**两种情况拿到的最高画质完全相同。** 开关只决定"菜单能不能打开"，决定不了"能拿到什么流"；
它唯一多出来的能力，是让你把画质**降到 540P**。

因此这两个 hook 已在 **v1.0.1 中彻底移除**（连同设置页那个「解锁视频清晰度」开关），
模块现在只做去广告与注入自检，声明与实际行为完全一致。

> 要真正拿到 1080P，只有服务端认账（例如真实会员账号）。客户端改不出服务端没有的文件。

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

1. 安装 APK。应用名是 **河马小手**，但**既不注册桌面入口，也没有任何界面**——桌面上和应用列表里都不会出现图标。
2. 在 LSPosed 里启用该模块并勾选作用域 `河马剧场`。**启用后即生效**，无需任何额外操作。
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
[OK]   解锁: UnlockAdVM.onClose  <- com.dz.business.video.unlock.ad.UnlockAdVM#onClose
结果     : 11/11 项全部匹配，模块可正常工作
======================================
目标应用版本: 3.11.1 (11031101)
```

- `[OK]` = 目标类与方法都在；`[MISS]` = 对不上，末尾会汇总缺失项并提示对照 smali 适配。
- App 升级后 R8 会重新混淆，靠这一段就能立刻定位是哪个 hook 失效了，不用等它静默不生效。
- **首次**注入（主进程）还会在屏幕上弹一次 Toast 汇总结果，之后不再弹。
  记「已提示过」用的是目标应用私有目录下的空标记文件
  `/data/data/com.dz.hmjc/files/.hmxs_selfcheck_shown`，删掉它即可让提示重新出现。

## 配置

**没有配置界面**。v1.0.2 起设置页被整体移除，三个功能开关（`block_ads` / `block_reader_ads` /
`block_video_unlock_ads`）保留为远程偏好读取，但没有写入方，恒为默认值 `true`，即全部功能始终开启。

> 保留 `ModuleConfig.bindRemotePreferences()` 这层间接的目的是：将来若接入别的配置渠道
> （例如 LSPosed 模块设置页），只需补写入方，hook 侧零改动。
>
> 顺带说明：LSPosed 通过 `getRemotePreferences` 给注入进程的偏好是**只读**的
> （写入抛 `UnsupportedOperationException: Read only implementation`），
> 早期版本曾因此打断过 `onPackageReady`，现在 `ModuleConfig` 已不提供写入路径。

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

### 已移除：清晰度开关

v1.0.1 移除。原因见「关于画质」——它不提升画质，只影响菜单能否打开。

历史记录（便于理解为什么不再做）：
- 曾 hook `ResolutionRateConfig.getResolutionRateSwitch()` 与 `com.dz.business.base.data.b.z5()` 强制返回 `true`
- 旧版按 `BasePlayer.setOption(VIDEO_BITRATE)` 覆盖码率的做法在 3.11.1 **不可行**：dz 的播放器（混淆为 `com.dz.platform.player.player.k`）只有读取方法 `y(Option)F`，没有写 bitrate 的 `setOption`，App 也不调用 `setDefaultResolution`；`AliPlayer` 是接口，无法这样 hook
- 因此清晰度这条路在客户端侧**没有任何可行解**，已整体放弃

## 目录结构

```text
XposedModule/
├── build.gradle.kts          # 开了 isMinifyEnabled + isShrinkResources，无 androidx 依赖
├── proguard-rules.pro        # 保住 Xposed 入口类名与反射点
├── src/main/
│   ├── AndroidManifest.xml   # 无 Activity、无 LAUNCHER 入口
│   ├── java/com/dz/hmxs/
│   │   ├── HippoXposedModule.kt   # 入口 + 11 个 hook + 注入自检
│   │   └── ModuleConfig.kt        # 只读开关（无 UI，恒为默认值）
│   ├── resources/META-INF/xposed/
│   │   ├── java_init.list        # 入口类 com.dz.hmxs.HippoXposedModule
│   │   ├── module.prop           # minApiVersion/targetApiVersion = 102
│   │   └── scope.list            # com.dz.hmjc
│   └── res/
│       ├── mipmap-xxxhdpi/       # 模块图标（供 LSPosed 列表显示）
│       └── values/strings.xml    # app_name / module_description
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
