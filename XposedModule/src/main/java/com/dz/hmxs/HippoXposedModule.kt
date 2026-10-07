package com.dz.hmxs

import android.app.Activity
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method

/**
 * 目标应用：河马剧场 com.dz.hmjc 3.11.1
 *
 * 该版本启用了 R8 混淆，业务类/方法名与旧版本不同。下面每个 hook 的类名与参数签名
 * 都是对照 apk_out 反编译产物逐个核对过的；升级 App 后必须重新对照 smali 再改。
 */
class HippoXposedModule : XposedModule() {
    companion object {
        private const val TAG = "HippoXposed"
        private const val TARGET_PACKAGE = "com.dz.hmjc"

        /** 模块自身的版本号；每次发版必须同步 +1，用于判断"模块是否更新过" */
        private const val MODULE_VERSION_CODE = 5

        /**
         * 指纹文件名，记录「上次提示时的 模块版本|宿主版本」。
         * 内容形如 `4|11031101`。放在目标应用私有目录下（LSPosed 远程偏好只读，写不进去）。
         */
        private const val FINGERPRINT_FILE = ".hmxs_checked_fingerprint"

        /**
         * DexKit 搜索目标：用**字符串特征**反查被 R8 混淆后的类名。
         *
         * 为什么用字符串而不是类名：R8 会重命名类/方法，但**不会改字符串常量**。
         * 宿主更新后混淆名一变，hardcoded 的 `loadClass("com.dz.platform.ad.a")` 立刻失效，
         * 而字符串特征仍能命中，于是 dexkit 可以把新类名反查出来。
         *
         * 字段含义：
         *  - desc      : 人类可读的描述，用于日志
         *  - knownName : 3.11.1 核对出的当前混淆名（作为对照基准）
         *  - scope     : 包名前缀，限定 DexKit 搜索范围，避免搜到无关类
         *  - strings   : 该类的字符串特征（取最具辨识度的几条）
         */
        private val DEX_TARGETS = listOf(
            DexTarget(
                desc = "AdManager",
                knownName = "com.dz.platform.ad.a",
                scope = "com.dz.platform.ad",
                strings = listOf("GlobalKV.adLazyInitNew:", "firstLoadAllowSdks:")
            ),
            DexTarget(
                desc = "ReaderAdManager",
                knownName = "com.dz.business.reader.ad.a",
                scope = "com.dz.business.reader.ad",
                strings = listOf("dzNative loadFeedAd sceneType = ", "king-AdReader")
            ),
            DexTarget(
                desc = "UnlockRewardAdLoader",
                knownName = "com.dz.business.video.unlock.ad.loader.reward.UnlockRewardAdLoader",
                scope = "com.dz.business.video.unlock.ad.loader.reward",
                strings = listOf("开始加载广告，广告位：", "广告过期时间")
            ),
            DexTarget(
                desc = "InterstitialAdUnlockLoader",
                knownName = "com.dz.business.video.unlock.ad.loader.interstitial.a",
                scope = "com.dz.business.video.unlock.ad.loader.interstitial",
                strings = listOf("插屏广告", "广告加载失败")
            ),
            DexTarget(
                desc = "UnlockAdVM",
                knownName = "com.dz.business.video.unlock.ad.UnlockAdVM",
                scope = "com.dz.business.video.unlock.ad",
                strings = listOf("正在加载中", "有缓存且未过期的广告，无需预加载")
            ),
            DexTarget(
                desc = "TeenMSImpl",
                knownName = "com.dz.business.teen.TeenMSImpl",
                scope = "com.dz.business.teen",
                // 这两条日志文案只出现在 TeenMSImpl.w1() 里，是该类独有的字符串特征
                strings = listOf("dialog 已关闭次数:", "dialog 上次展示是否同一天：")
            )
        )

        /**
         * 青少年模式弹窗的拦截目标（3.11.1）。
         *
         * `TeenMSImpl` 是 `com.dz.business.base.teen.b`（`.source "TeenMS.kt"`）的唯一实现，
         * `w1(String currentTab)` 返回非 null 的 TeenDialogIntent 时 MainActivity 就会弹窗。
         */
        private const val TEEN_IMPL_CLASS = "com.dz.business.teen.TeenMSImpl"
        private const val TEEN_DIALOG_METHOD = "w1"
    }

    private var hooksInstalled = false

    /**
     * DexKit 搜索目标：用字符串特征反查混淆后的类名。
     * @param desc      人类可读描述（日志用）
     * @param knownName 3.11.1 核对出的当前混淆名（对照基准）
     * @param scope     包名前缀，限定 DexKit 搜索范围，避免命中无关类
     * @param strings   该类的字符串特征（R8 不改字符串常量，故可作稳定指纹）
     */
    private data class DexTarget(
        val desc: String,
        val knownName: String,
        val scope: String,
        val strings: List<String>
    )

    /** libdexkit.so 是否已加载（进程内只需成功一次） */
    @Volatile
    private var nativeLoaded = false
    private val nativeLock = Any()

    /** 宿主 Application 的 Context（attachBaseContext 时捕获，DexKit 提取 so 要用） */
    @Volatile
    private var hostContext: android.content.Context? = null

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE || !param.isFirstPackage || hooksInstalled) return

        try {
            ModuleConfig.bindRemotePreferences(getRemotePreferences(ModuleConfig.PREFS_NAME))
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "Unable to bind remote preferences: ${e.message}")
        }

        // hook 始终用 hardcoded 的 3.11.1 混淆名安装，不依赖 DexKit
        // （DexKit 只负责"报告 + 发现新名字"，失败也不影响模块工作）。
        val classLoader = param.classLoader
        hookAdManager(classLoader)
        hookReaderAdManager(classLoader)
        hookVideoUnlockAds(classLoader)
        hookTeenMode(classLoader)
        hooksInstalled = true
        log(Log.INFO, TAG, "Hook groups attempted; per-hook result logged above.")

        // 捕获宿主 Context：DexKit 需要往宿主私有目录写 libdexkit.so。
        // attachBaseContext 是 Application 生命周期里最早能拿到 Context 的点。
        captureHostContext()

        // 自检走 DexKit，耗时较长（要在宿主进程里解析整个 dex），
        // 因此放到独立线程，避免拖慢宿主启动。
        val processName = currentProcessName()
        val isMainProcess = processName == param.packageName
        Thread {
            try {
                selfCheck(param, processName, isMainProcess)
            } catch (e: Throwable) {
                log(Log.WARN, TAG, "自检线程异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    /**
     * 在 Application.attachBaseContext 时记下宿主 Context。
     * 该方法的声明在 ContextWrapper 上（Application 未重写），因此过滤只保留 Application 那一次。
     */
    private fun captureHostContext() {
        try {
            hookMethod(
                android.content.ContextWrapper::class.java,
                "attachBaseContext",
                android.content.Context::class.java
            ).invoke { chain ->
                if (hostContext == null && chain.thisObject is android.app.Application) {
                    hostContext = chain.args.firstOrNull() as? android.content.Context
                }
                chain.proceed()
            }
            log(Log.INFO, TAG, "上下文捕获已挂载: ContextWrapper.attachBaseContext")
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "上下文捕获挂载失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 等宿主 Context 就绪（最多约 5 秒），供自检线程使用 */
    private fun awaitHostContext(): android.content.Context? {
        repeat(50) {
            hostContext?.let { return it }
            try {
                Thread.sleep(100)
            } catch (ignored: InterruptedException) {
            }
        }
        return hostContext
    }

    // ------------------------------------------------------------------
    // 注入自检（DexKit 版）
    //
    // 用字符串特征反查 DEX，回答两个问题：
    //   1. 3.11.1 核对出的混淆名是否仍然命中（模块当前 hook 的类名是否还有效）
    //   2. 若失效，新名字是什么（DexKit 按特征搜出来的结果）
    // ------------------------------------------------------------------
    private fun selfCheck(param: PackageReadyParam, processName: String, isMainProcess: Boolean) {
        val framework = try {
            "${frameworkName} ${frameworkVersion} (API $apiVersion)"
        } catch (e: Throwable) {
            "未知"
        }

        // 先算指纹与上次比：只有「首次启用 / 模块更新 / 宿主更新」才值得打全量报告 + 弹提示
        val hostFp = hostFingerprint(param)
        val fingerprint = "$MODULE_VERSION_CODE|$hostFp"
        val lastFingerprint = readLastFingerprint(param)

        log(Log.INFO, TAG, "========== 河马小手 注入自检 ==========")
        log(Log.INFO, TAG, "目标应用 : ${param.packageName}")
        log(Log.INFO, TAG, "当前进程 : $processName")
        log(Log.INFO, TAG, "框架     : $framework")
        log(Log.INFO, TAG, "模块版本 : $MODULE_VERSION_CODE")
        log(Log.INFO, TAG, "宿主指纹 : $hostFp")
        log(Log.INFO, TAG, "指纹     : $fingerprint（上次 $lastFingerprint）")

        if (fingerprint == lastFingerprint) {
            // 指纹未变 —— 宿主与模块都没更新，说明 hook 目标类名仍是上次核对过的那套。
            // 这类情况占绝大多数冷启动，直接跳过耗时的 dex 解析。
            log(Log.INFO, TAG, "状态     : 模块与宿主均无变化，跳过类名核对")
            log(Log.INFO, TAG, "======================================")
            return
        }

        val summary = runDexKitCheck(param.classLoader)
        log(Log.INFO, TAG, "======================================")
        log(Log.INFO, TAG, "结果     : $summary")

        // 记下本次指纹，下次同样输入就不再重复检查
        writeLastFingerprint(param, fingerprint)

        // 屏幕提示只在主进程弹，且只在指纹变化时弹（= 首次启用 / 模块更新 / 宿主更新）
        if (isMainProcess) {
            showToast("河马小手 注入自检：$summary")
        }
    }

    private fun runDexKitCheck(hostClassLoader: ClassLoader): String {
        val found = ArrayList<String>()
        val missing = ArrayList<String>()

        // DexKit 是 JNI 库。模块代码运行在**宿主进程**里，模块 APK 的 lib/ 不会被自动
        // 挂进进程的 native 搜索路径，因此必须先自行提取并 System.load。
        val ctx = awaitHostContext()
        if (ctx == null) {
            return "拿不到宿主 Context，本次跳过类名核对"
        }
        if (!ensureDexKitNativeLoaded(ctx)) {
            return "DexKit 不可用（native 库加载失败），本次跳过类名核对"
        }

        val bridge = try {
            // 注意：必须传**宿主应用**的 ClassLoader。
            // 传模块自己的 classLoader 只会扫到模块那几个类，宿主的业务类一个都看不到。
            org.luckypray.dexkit.DexKitBridge.create(hostClassLoader, true)
        } catch (e: Throwable) {
            // DexKit 初始化失败时给出明确提示，而不是静默通过
            log(Log.WARN, TAG, "DexKit 初始化失败: ${e.javaClass.simpleName}: ${e.message}")
            return "DexKit 不可用（${e.javaClass.simpleName}），本次跳过类名核对"
        }

        try {
            for (t in DEX_TARGETS) {
                val hits = try {
                    bridge.findClass(
                        org.luckypray.dexkit.query.FindClass().apply {
                            searchPackages(t.scope)
                            matcher(
                                org.luckypray.dexkit.query.matchers.ClassMatcher()
                                    .usingStrings(t.strings)
                            )
                        }
                    ).map { it.name }
                } catch (e: Throwable) {
                    log(Log.WARN, TAG, "DexKit 搜索失败 [${t.desc}]: ${e.message}")
                    emptyList()
                }

                when {
                    hits.contains(t.knownName) -> {
                        found.add(t.desc)
                        log(Log.INFO, TAG, "[OK]   ${t.desc}  混淆名仍有效: ${t.knownName}")
                    }
                    hits.isEmpty() -> {
                        missing.add(t.desc)
                        log(Log.WARN, TAG, "[MISS] ${t.desc}  特征串未命中任何类（已知名 ${t.knownName}）")
                    }
                    else -> {
                        // 关键场景：宿主更新后混淆名变了，这里直接给出新名字
                        missing.add(t.desc)
                        log(Log.WARN, TAG, "[CHANGED] ${t.desc}  混淆名已变化")
                        log(Log.WARN, TAG, "          旧: ${t.knownName}")
                        hits.forEach { log(Log.WARN, TAG, "          新: $it") }
                    }
                }
            }
        } finally {
            try {
                bridge.close()
            } catch (ignored: Throwable) {
            }
        }

        val total = DEX_TARGETS.size
        return when {
            missing.isEmpty() -> "$total/$total 项全部匹配，模块可正常工作"
            found.isEmpty() -> "0/$total 项匹配，所有混淆名可能都已变化，请对照 smali 更新"
            else -> "${found.size}/$total 项匹配，${missing.size} 项异常：${missing.joinToString("、")}"
        }
    }

    /**
     * 加载 libdexkit.so。
     *
     * 为什么不能直接 `System.loadLibrary("dexkit")`：
     * 模块代码被注入到**宿主进程**执行，模块 APK 的 `lib/<abi>/` 并不在宿主进程的
     * native 库搜索路径里，直接 loadLibrary 会抛 `UnsatisfiedLinkError`。
     *
     * 为什么写到宿主私有目录：本方法在宿主进程里跑，进程 uid 是**宿主的**，
     * 对模块自己的 `/data/user/0/com.dz.hmxs/` 没有写权限（会 ENOENT/EACCES）。
     * 因此 so 落到宿主自己的私有目录（`files/` 下），模块 APK 本身作为 so 的来源。
     *
     * @return 加载成功（或已加载过）返回 true
     */
    private fun ensureDexKitNativeLoaded(ctx: android.content.Context): Boolean {
        if (nativeLoaded) return true
        synchronized(nativeLock) {
            if (nativeLoaded) return true
            // 先试常规路径：若框架恰好已把 so 挂进搜索路径，这一步就能成功
            try {
                System.loadLibrary("dexkit")
                nativeLoaded = true
                log(Log.INFO, TAG, "libdexkit.so 已通过 loadLibrary 加载")
                return true
            } catch (ignored: Throwable) {
            }

            try {
                // so 来源 = 模块 APK；落盘位置 = 宿主私有目录
                val moduleInfo = getModuleApplicationInfo()
                val apkPath = moduleInfo?.sourceDir
                if (apkPath == null) {
                    log(Log.WARN, TAG, "取不到模块 APK 路径，无法提取 libdexkit.so")
                    return false
                }
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val entry = "lib/$abi/libdexkit.so"

                val outDir = java.io.File(ctx.filesDir, "dexkit_native")
                if (!outDir.exists()) outDir.mkdirs()
                val soFile = java.io.File(outDir, "libdexkit_$abi.so")

                // 已提取过且非空就复用，避免每次启动都解压
                if (!soFile.exists() || soFile.length() == 0L) {
                    java.util.zip.ZipFile(apkPath).use { zip ->
                        val ze = zip.getEntry(entry)
                        if (ze == null) {
                            log(Log.WARN, TAG, "模块 APK 内不存在 $entry")
                            return false
                        }
                        zip.getInputStream(ze).use { input ->
                            soFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                System.load(soFile.absolutePath)
                nativeLoaded = true
                log(Log.INFO, TAG, "libdexkit.so 已加载: $entry -> ${soFile.absolutePath}")
                return true
            } catch (e: Throwable) {
                log(Log.WARN, TAG, "提取/加载 libdexkit.so 失败: ${e.javaClass.simpleName}: ${e.message}")
                return false
            }
        }
    }

    /**
     * 宿主"版本指纹"：用安装包自身的 **lastModified + 大小** 代替 versionCode。
     *
     * 为什么不用 versionCode：`ApplicationInfo` 上没有 versionCode（那是 `PackageInfo` 的），
     * 而 `onPackageReady` 阶段拿不到可靠的 Context。安装包文件的时间戳与大小在**每次安装/更新时必变**，
     * 作为"宿主是否更新过"的判据完全够用，且不依赖任何 Context。
     */
    private fun hostFingerprint(param: PackageReadyParam): String {
        return try {
            val src = param.applicationInfo?.sourceDir ?: return "unknown"
            val f = java.io.File(src)
            "${f.lastModified()}_${f.length()}"
        } catch (e: Throwable) {
            "unknown"
        }
    }

    /**
     * 指纹文件：放在目标应用私有目录 `files/` 下。
     * 路径直接由 `ApplicationInfo.dataDir` 拼出 —— 不依赖 Context，
     * 因此 `onPackageReady` 阶段（Application 尚未创建）也能正常读写。
     * LSPosed 的远程偏好是只读的，写不进去，所以只能自己落文件。
     */
    private fun fingerprintFile(param: PackageReadyParam): java.io.File? {
        return try {
            val dataDir = param.applicationInfo?.dataDir ?: return null
            val dir = java.io.File(dataDir, "files")
            if (!dir.exists()) dir.mkdirs()
            java.io.File(dir, FINGERPRINT_FILE)
        } catch (e: Throwable) {
            null
        }
    }

    private fun readLastFingerprint(param: PackageReadyParam): String = try {
        fingerprintFile(param)?.takeIf { it.exists() }?.readText()?.trim().orEmpty().ifEmpty { "(无)" }
    } catch (e: Throwable) {
        "(无)"
    }

    private fun writeLastFingerprint(param: PackageReadyParam, value: String) {
        try {
            fingerprintFile(param)?.writeText(value)
        } catch (ignored: Throwable) {
        }
    }

    private fun showToast(text: String) {
        try {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var tries = 0
            val task = object : Runnable {
                override fun run() {
                    val ctx = currentApplication()
                    if (ctx != null) {
                        try {
                            android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show()
                        } catch (ignored: Throwable) {
                        }
                        return
                    }
                    if (++tries < 20) handler.postDelayed(this, 500)
                }
            }
            handler.postDelayed(task, 1500)
        } catch (ignored: Throwable) {
        }
    }

    /** 当前进程名（/proc/self/cmdline），用于区分主进程和 :pushservice */
    private fun currentProcessName(): String = try {
        java.io.File("/proc/self/cmdline").readText().trim().trim('\u0000')
    } catch (e: Throwable) {
        "unknown"
    }

    private fun currentApplication(): android.app.Application? = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        at.getMethod("getApplication").invoke(thread) as? android.app.Application
    } catch (e: Throwable) {
        null
    }

    /**
     * 每个 hook 独立包装：单个失败不拖垮其余，成功/失败都打日志，
     * 出问题时直接 logcat 过滤 HippoXposed 就能看出哪条没装上。
     */
    private fun tryHook(name: String, block: () -> Unit) {
        try {
            block()
            log(Log.INFO, TAG, "Hook installed: $name")
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "Hook FAILED: $name -> ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun hookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>
    ): (((XposedInterface.Chain) -> Any?) -> XposedInterface.HookHandle) {
        val method: Method = clazz.getDeclaredMethod(methodName, *parameterTypes)
        return { callback ->
            hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? = callback(chain)
            })
        }
    }

    /** 按方法名+参数个数反射调用，用于回调接口，避免硬编码接口方法的完整签名。 */
    private fun callMethod(target: Any?, methodName: String, vararg args: Any?): Boolean {
        val receiver = target ?: return false
        val method = (receiver.javaClass.methods + receiver.javaClass.declaredMethods).firstOrNull {
            it.name == methodName && it.parameterCount == args.size
        } ?: return false
        method.isAccessible = true
        return try {
            method.invoke(receiver, *args)
            true
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "callMethod($methodName) failed: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // 1. 启动页 / 信息流 / 视频前贴片广告
    //
    // 3.11.1 中 AdManager 混淆为 com.dz.platform.ad.a（smali .source "AdManager.kt"）
    //  - l  是 public static synthetic，receiver 作为第一个实参；旧版按实例方法 25 参写法找不到方法
    //  - A / B 参数里的 Boolean 是装箱类型，必须传 java.lang.Boolean 而不是 boolean.class
    // ------------------------------------------------------------------
    private fun hookAdManager(classLoader: ClassLoader) {
        if (!ModuleConfig.blockAds) return

        val adManagerClass = try {
            classLoader.loadClass("com.dz.platform.ad.a")
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "AdManager class not found: ${e.message}")
            return
        }

        val int = Int::class.javaPrimitiveType!!
        val bool = Boolean::class.javaPrimitiveType!!
        val long = Long::class.javaPrimitiveType!!
        val double = Double::class.javaPrimitiveType!!
        val str = String::class.java
        val obj = Any::class.java
        val intBox = Int::class.javaObjectType
        val boolBox = Boolean::class.javaObjectType

        tryHook("AdManager.l() [static, block ad load]") {
            hookMethod(
                adManagerClass, "l",
                adManagerClass, Activity::class.java,
                int, int, int, int,
                classLoader.loadClass("com.dz.platform.ad.f"),
                str, intBox, str,
                int, int, int,
                bool, bool,
                str, str, str, boolBox,
                classLoader.loadClass("com.dianzhong.base.data.loadparam.WelfareAnchorParams"),
                intBox, intBox, double,
                str, str,
                classLoader.loadClass("com.dianzhong.base.data.loadparam.EcMallOrderRebateParams"),
                long,
                classLoader.loadClass("com.dz.platform.ad.callback.e"),
                int, obj
            ).invoke {
                log(Log.DEBUG, TAG, "AdManager.l() blocked")
                null
            }
        }

        tryHook("AdManager.A() [block ad show]") {
            hookMethod(
                adManagerClass, "A",
                int, str, int, int, str, str, boolBox
            ).invoke {
                log(Log.DEBUG, TAG, "AdManager.A() blocked")
                null
            }
        }

        tryHook("AdManager.B() [block ad show]") {
            hookMethod(
                adManagerClass, "B",
                int, str, str, boolBox
            ).invoke {
                log(Log.DEBUG, TAG, "AdManager.B() blocked")
                null
            }
        }

        // 开屏广告：走的是独立链路，不经过 l/A/B，所以之前去不掉。
        // v(SplashAdVo, boolean, boolean)Z 是"是否满足开屏广告加载条件"的总闸，
        // 三处调用点（SplashAdLoadRepository / BaseSplashVM / SplashInitKt$h）都会走它。
        // 返回 false 会进入 App 自己的正规无广告分支：
        //   日志"不满足广告加载条件，停止加载广告" + SplashAdLoadRepository.c()
        //   + 返回 "不需要展示开屏广告"，因此不会卡在开屏等待广告。
        val splashAdVo = classLoader.loadClass("com.dz.platform.ad.vo.SplashAdVo")
        tryHook("AdManager.v() [splash => not eligible, no splash ad]") {
            hookMethod(adManagerClass, "v", splashAdVo, bool, bool).invoke {
                log(Log.DEBUG, TAG, "AdManager.v(splash) forced false -> no splash ad")
                false
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. 阅读页广告
    //
    // ReaderAdManager 混淆为 com.dz.business.reader.ad.a（.source "ReaderAdManager.kt"）
    // 回调参数是 com.dz.business.reader.ad.callback.c；旧版写的 AdLoadCallback 类在 3.11.1 不存在。
    // ------------------------------------------------------------------
    private fun hookReaderAdManager(classLoader: ClassLoader) {
        if (!ModuleConfig.blockReaderAds) return

        val readerAdManagerClass = try {
            classLoader.loadClass("com.dz.business.reader.ad.a")
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "ReaderAdManager class not found: ${e.message}")
            return
        }

        val paramClass = classLoader.loadClass("com.dz.business.reader.ad.data.ReaderAdLoadParam")
        val callbackClass = classLoader.loadClass("com.dz.business.reader.ad.callback.c")

        tryHook("ReaderAdManager.a() [cache hit => skip load]") {
            hookMethod(readerAdManagerClass, "a", paramClass, callbackClass).invoke {
                log(Log.DEBUG, TAG, "ReaderAdManager.a() blocked")
                true
            }
        }

        tryHook("ReaderAdManager.e() [block load]") {
            hookMethod(readerAdManagerClass, "e", paramClass, callbackClass).invoke {
                log(Log.DEBUG, TAG, "ReaderAdManager.e() blocked")
                null
            }
        }

        tryHook("ReaderAdManager.h() [block load]") {
            hookMethod(readerAdManagerClass, "h", paramClass, callbackClass).invoke {
                log(Log.DEBUG, TAG, "ReaderAdManager.h() blocked")
                null
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. 视频解锁广告（奖励 / 插屏 / 挂屏）
    //
    // InterstitialAdUnlockLoader 混淆为 ...loader.interstitial.a
    // 思路：广告真正要展示时拦截展示入口，直接把 loader.c 的完成回调按成功上报，
    //      点解锁立刻拿结果，不进广告播放；再兜底把完成回调里的 complete=false 改写成 true。
    // ------------------------------------------------------------------
    private fun hookVideoUnlockAds(classLoader: ClassLoader) {
        if (!ModuleConfig.blockVideoUnlockAds) return

        val str = String::class.java
        val bool = Boolean::class.javaPrimitiveType!!

        val loaderC = try {
            classLoader.loadClass("com.dz.business.video.unlock.ad.loader.c")
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "loader.c not found: ${e.message}")
            return
        }

        // 奖励解锁：UnlockRewardAdLoader.G(activity, bean, chapter, adConfig, callback, Integer, String)
        try {
            val rewardLoader = classLoader.loadClass(
                "com.dz.business.video.unlock.ad.loader.reward.UnlockRewardAdLoader"
            )
            tryHook("UnlockRewardAdLoader.G() [skip show => report success]") {
                hookMethod(
                    rewardLoader, "G",
                    Activity::class.java,
                    classLoader.loadClass("com.dz.business.video.unlock.ad.loader.reward.RewardAdUnlockBean"),
                    classLoader.loadClass("com.dz.business.base.data.bean.ChapterInfoVo"),
                    classLoader.loadClass("com.dz.business.base.data.bean.AdConfigVo"),
                    loaderC,
                    Int::class.javaObjectType,
                    str
                ).invoke { chain ->
                    log(Log.DEBUG, TAG, "UnlockRewardAdLoader.G() bypassed -> d0(true)")
                    callMethod(chain.args[4], "d0", true, null)
                    null
                }
            }
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "Reward loader hook skipped: ${e.message}")
        }

        // 插屏解锁：interstitial.a.t(activity, bean, chapter, adConfig, callback)
        try {
            val interstitialLoader = classLoader.loadClass(
                "com.dz.business.video.unlock.ad.loader.interstitial.a"
            )
            tryHook("InterstitialLoader.t() [skip show => report success]") {
                hookMethod(
                    interstitialLoader, "t",
                    Activity::class.java,
                    classLoader.loadClass(
                        "com.dz.business.video.unlock.ad.loader.interstitial.InterstitialAdUnlockBean"
                    ),
                    classLoader.loadClass("com.dz.business.base.data.bean.ChapterInfoVo"),
                    classLoader.loadClass("com.dz.business.base.data.bean.AdConfigVo"),
                    loaderC
                ).invoke { chain ->
                    log(Log.DEBUG, TAG, "InterstitialLoader.t() bypassed -> onClose(true)")
                    callMethod(chain.args[4], "onClose", true)
                    null
                }
            }
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "Interstitial loader hook skipped: ${e.message}")
        }

        // 兜底：完成回调里 complete=false 一律改写成 true（覆盖挂屏等未单独拦截的路径）
        try {
            val unlockVm = classLoader.loadClass("com.dz.business.video.unlock.ad.UnlockAdVM")
            tryHook("UnlockAdVM.d0() [force complete=true]") {
                hookMethod(unlockVm, "d0", bool, str).invoke { chain ->
                    val args = chain.args.toTypedArray()
                    if (args[0] != true) {
                        log(Log.DEBUG, TAG, "UnlockAdVM.d0(false) forced to true")
                        args[0] = true
                    }
                    chain.proceed(args)
                }
            }
            tryHook("UnlockAdVM.onClose() [force complete=true]") {
                hookMethod(unlockVm, "onClose", bool).invoke { chain ->
                    val args = chain.args.toTypedArray()
                    if (args[0] != true) {
                        log(Log.DEBUG, TAG, "UnlockAdVM.onClose(false) forced to true")
                        args[0] = true
                    }
                    chain.proceed(args)
                }
            }
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "UnlockAdVM hook skipped: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 4. 青少年模式弹窗
    //
    // 弹窗决策链（3.11.1）：
    //   MainActivity.showTeenDialog
    //     → TeenMSImpl.w1(currentTab)          返回非 null 就会弹窗
    //         → teen.data.a.k() != 1 → return null     ← 第一道闸门（开关）
    //         → 关闭次数 / 间隔 / 同日判断
    //         → teen.utils.a.d()                        ← 第二道闸门
    //         → teen.data.a.i().contains(tabIndex)      ← 第三道闸门（配置的 tab 白名单）
    //         → TeenMR.teenModeDialog()
    //
    // 拦截点选在 `w1()` 本身，让它返回 null：
    //   · MainActivity 里是 `if-eqz v0, :cond_0` —— 拿到 null 就直接跳过弹窗分支，
    //     既不会构造 DialogRouteIntent，也不会进 PriorityTaskManager 排队；
    //   · `w1()` 只被 MainActivity 这一处调用（另一处是它自己的 lambda），
    //     拦它不会波及其它功能。
    //
    // ⚠️ 为什么**不** hook 第一道闸门 `teen.data.a.k()`（曾用过这个方案，已弃用）：
    //     `k()` 在 3.11.1 里有两处调用 ——
    //       L234 在 `w1()` 里（弹窗判定）
    //       L147 在 `n()` 里（发事件：k()==1 时带 TeenConfigVo，否则带 null）
    //     而 `n()` 的调用方是 ShareCodeWXDialog。把 `k()` 强制成 0 会连带把那个事件的
    //     参数从配置对象改成 null，属于本功能之外的副作用。故改为只拦 `w1()`。
    // ------------------------------------------------------------------
    private fun hookTeenMode(classLoader: ClassLoader) {
        if (!ModuleConfig.blockTeenModeDialog) return

        try {
            val teenMsImpl = classLoader.loadClass(TEEN_IMPL_CLASS)
            tryHook("TeenMode.w1() [return null => no dialog]") {
                hookMethod(teenMsImpl, TEEN_DIALOG_METHOD, String::class.java).invoke { chain ->
                    // 先取原返回值：非 null 才说明这次本会弹窗，被我们拦下；
                    // 为 null 则说明本就不弹（频率限制/白名单等原因），本次拦截无实际作用。
                    val origin = try {
                        chain.proceed()
                    } catch (e: Throwable) {
                        null
                    }
                    if (origin == null) {
                        log(Log.DEBUG, TAG, "teen w1() 原返回 null（本就不弹）")
                    } else {
                        log(Log.INFO, TAG, "teen w1() 原返回=${origin.javaClass.name} -> 拦下，弹窗不显示")
                    }
                    null
                }
            }
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "TeenMode hook skipped: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
