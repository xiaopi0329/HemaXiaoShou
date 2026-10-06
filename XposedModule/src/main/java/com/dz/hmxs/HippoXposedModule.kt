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

        /** 标记"首次注入提示已展示"的空文件，放在目标应用私有目录下 */
        private const val MARKER_FILE = ".hmxs_selfcheck_shown"

        /**
         * 注入自检清单：每个 hook 的目标类 + 方法名。
         * 目标 App 升级后 R8 会重新混淆，这里能在首次注入时直接点名哪一项对不上，
         * 不用等 hook 静默失效再排查。
         */
        private val HOOK_TARGETS = listOf(
            Triple("广告: AdManager.l (拦截加载)", "com.dz.platform.ad.a", "l"),
            Triple("广告: AdManager.A (拦截展示)", "com.dz.platform.ad.a", "A"),
            Triple("广告: AdManager.B (拦截展示)", "com.dz.platform.ad.a", "B"),
            Triple("广告: AdManager.v (开屏判定)", "com.dz.platform.ad.a", "v"),
            Triple("阅读页: ReaderAdManager.a", "com.dz.business.reader.ad.a", "a"),
            Triple("阅读页: ReaderAdManager.e", "com.dz.business.reader.ad.a", "e"),
            Triple("阅读页: ReaderAdManager.h", "com.dz.business.reader.ad.a", "h"),
            Triple(
                "解锁: UnlockRewardAdLoader.G",
                "com.dz.business.video.unlock.ad.loader.reward.UnlockRewardAdLoader", "G"
            ),
            Triple(
                "解锁: InterstitialLoader.t",
                "com.dz.business.video.unlock.ad.loader.interstitial.a", "t"
            ),
            Triple("解锁: UnlockAdVM.d0", "com.dz.business.video.unlock.ad.UnlockAdVM", "d0"),
            Triple("解锁: UnlockAdVM.onClose", "com.dz.business.video.unlock.ad.UnlockAdVM", "onClose"),
            Triple(
                "清晰度: ResolutionRateConfig.getResolutionRateSwitch",
                "com.dz.business.base.data.bean.ResolutionRateConfig", "getResolutionRateSwitch"
            ),
            Triple("清晰度: data.b.z5", "com.dz.business.base.data.b", "z5")
        )
    }

    private var hooksInstalled = false

    /** 保证注入后的上下文处理只走一次（attachBaseContext 与兜底轮询二选一） */
    private val contextReady = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE || !param.isFirstPackage || hooksInstalled) return

        try {
            ModuleConfig.bindRemotePreferences(getRemotePreferences(ModuleConfig.PREFS_NAME))
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "Unable to bind remote preferences: ${e.message}")
        }

        // 先自检再装 hook：类名对不上时日志里能直接看到是哪一项
        val processName = currentProcessName()
        val result = selfCheck(param, processName)

        val classLoader = param.classLoader
        hookAdManager(classLoader)
        hookReaderAdManager(classLoader)
        hookVideoUnlockAds(classLoader)
        hookQualitySwitch(classLoader)
        hooksInstalled = true
        log(Log.INFO, TAG, "Hook groups attempted; per-hook result logged above.")

        // 屏幕提示只在主进程做（:pushservice 不做，避免重复弹）且只弹首次
        afterInjection(result, param.packageName, processName == param.packageName)
    }

    // ------------------------------------------------------------------
    // 注入自检：核对每个 hook 的目标类/方法是否还在
    // ------------------------------------------------------------------
    private fun selfCheck(param: PackageReadyParam, processName: String): String {
        val framework = try {
            "${frameworkName} ${frameworkVersion} (API $apiVersion)"
        } catch (e: Throwable) {
            "未知"
        }

        log(Log.INFO, TAG, "========== 河马小手 注入自检 ==========")
        log(Log.INFO, TAG, "目标应用 : ${param.packageName}")
        log(Log.INFO, TAG, "当前进程 : $processName")
        log(Log.INFO, TAG, "框架     : $framework")

        val missing = ArrayList<String>()
        for ((desc, className, methodName) in HOOK_TARGETS) {
            val found = try {
                param.classLoader.loadClass(className).declaredMethods.any { it.name == methodName }
            } catch (e: Throwable) {
                false
            }
            if (found) {
                log(Log.INFO, TAG, "[OK]   $desc  <- $className#$methodName")
            } else {
                missing.add(desc)
                log(Log.WARN, TAG, "[MISS] $desc  <- $className#$methodName 不存在")
            }
        }

        val total = HOOK_TARGETS.size
        val summary = if (missing.isEmpty()) {
            "$total/$total 项全部匹配，模块可正常工作"
        } else {
            "${total - missing.size}/$total 项匹配，缺失 ${missing.size} 项：${missing.joinToString("、")}"
        }
        if (missing.isEmpty()) {
            log(Log.INFO, TAG, "结果     : $summary")
        } else {
            log(Log.WARN, TAG, "结果     : $summary")
            log(Log.WARN, TAG, "提示     : 该版本可能改了混淆名，请对照 apk 反编译产物更新 HOOK_TARGETS")
        }
        log(Log.INFO, TAG, "======================================")
        return summary
    }

    /** 当前进程名（/proc/self/cmdline），用于区分主进程和 :pushservice */
    private fun currentProcessName(): String = try {
        java.io.File("/proc/self/cmdline").readText().trim().trim('\u0000')
    } catch (e: Throwable) {
        "unknown"
    }

    /**
     * 注入之后要做两件事：补记目标 App 版本、按需弹一次自检提示。
     * 注入发生在 Application 创建之前，此时拿不到 Context，所以用两条路：
     *   1) hook Application.attachBaseContext —— 最可靠，App 启动必然调用
     *   2) 兜底轮询反射 ActivityThread.getApplication()
     * 两条路都用 AtomicBoolean 保证只处理一次。
     */
    private fun afterInjection(summary: String, targetPkg: String, showToast: Boolean) {
        try {
            // attachBaseContext 声明在 ContextWrapper 上（Application 并未重写），
            // 只对 Application 那一次生效，其余 ContextWrapper 直接放行。
            hookMethod(
                android.content.ContextWrapper::class.java,
                "attachBaseContext",
                android.content.Context::class.java
            ).invoke { chain ->
                if (chain.thisObject is android.app.Application) {
                    onContextReady(chain.args.firstOrNull(), summary, targetPkg, showToast)
                }
                chain.proceed()
            }
            log(Log.INFO, TAG, "上下文钩子已挂载: ContextWrapper.attachBaseContext")
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "上下文钩子挂载失败: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var tries = 0
            val task = object : Runnable {
                override fun run() {
                    val ctx = currentApplication()
                    if (ctx != null) {
                        onContextReady(ctx, summary, targetPkg, showToast)
                        return
                    }
                    if (++tries < 20) handler.postDelayed(this, 500)
                }
            }
            handler.postDelayed(task, 1200)
        } catch (ignored: Throwable) {
        }
    }

    private fun onContextReady(ctx: Any?, summary: String, targetPkg: String, allowToast: Boolean) {
        if (ctx !is android.content.Context) return
        if (!contextReady.compareAndSet(false, true)) return

        // 版本信息只能拿到 Context 之后读（ApplicationInfo 上没有 versionName/versionCode）
        try {
            val pi = ctx.packageManager.getPackageInfo(targetPkg, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode
            } else {
                legacyVersionCode(pi)
            }
            log(Log.INFO, TAG, "目标应用版本: ${pi.versionName} ($code)")
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "读取目标应用版本失败: ${e.message}")
        }

        if (!allowToast) return

        // LSPosed 远程偏好是只读的，没法用偏好记"已提示过"，
        // 就在目标应用自己的私有目录放一个空标记文件（0 字节，随时可删）。
        val marker = java.io.File(ctx.filesDir, MARKER_FILE)
        val shown = try {
            marker.exists()
        } catch (e: Throwable) {
            false
        }
        if (shown) {
            log(Log.INFO, TAG, "自检提示此前已展示过，本次不弹（如需重弹请删除 ${marker.absolutePath}）")
            return
        }
        try {
            android.widget.Toast.makeText(
                ctx, "河马小手 注入自检：$summary", android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (ignored: Throwable) {
        }
        try {
            marker.createNewFile()
        } catch (ignored: Throwable) {
        }
    }

    private fun currentApplication(): android.app.Application? = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        at.getMethod("getApplication").invoke(thread) as? android.app.Application
    } catch (e: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(pi: android.content.pm.PackageInfo): Long = pi.versionCode.toLong()

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
    // 4. 清晰度解锁
    //
    // 3.11.1 里 dz BasePlayer 混淆为 ...player.k，只有 y(Option)F 读取方法，
    // 没有任何写 bitrate 的 setOption；App 侧也不调用 setDefaultResolution。
    // 旧版 hook 的 BasePlayer.setOption / AliPlayer.setOption(Option, Object) 在本版本不存在。
    // 实际限制清晰度的是"清晰度开关"：
    //   - ResolutionRateConfig.getResolutionRateSwitch()  服务端开关（关闭时 VideoMSImpl 强制 Uf("720P")）
    //   - com.dz.business.base.data.b.z5()               本地开关（关闭时 BaseResolutionDialogComp 直接 dismiss 弹窗）
    // 两个都强制为 true 才能放开清晰度切换。
    // ------------------------------------------------------------------
    private fun hookQualitySwitch(classLoader: ClassLoader) {
        if (!ModuleConfig.unlockQuality) return

        try {
            val rateConfig = classLoader.loadClass(
                "com.dz.business.base.data.bean.ResolutionRateConfig"
            )
            tryHook("ResolutionRateConfig.getResolutionRateSwitch() [force true]") {
                hookMethod(rateConfig, "getResolutionRateSwitch").invoke {
                    log(Log.DEBUG, TAG, "resolutionRateSwitch forced true")
                    true
                }
            }
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "ResolutionRateConfig hook skipped: ${e.message}")
        }

        try {
            val dataRepo = classLoader.loadClass("com.dz.business.base.data.b")
            tryHook("data.b.z5() [force true]") {
                hookMethod(dataRepo, "z5").invoke {
                    log(Log.DEBUG, TAG, "resolution local switch forced true")
                    true
                }
            }
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "data.b.z5() hook skipped: ${e.message}")
        }

    }
}
