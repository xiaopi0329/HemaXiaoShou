# =====================================================================
#  河马小手 / HemaXiaoShou —— ProGuard (R8) 规则
#  开 isMinifyEnabled 后生效：在瘦身的同时保住所有运行时反射点。
# =====================================================================

# ---------- 1. Xposed 模块入口 ----------
# libxposed 通过 META-INF/xposed/java_init.list 中的全限定类名反射加载入口，
# 类名与 public <init> 都不能被 R8 改动。
-keep class com.dz.hmxs.HippoXposedModule { public <init>(...); }

# 让 R8 同步 java_init.list 中引用的类名（上面 keep 已保证类名不变，这是双保险；
# 若将来移除该 keep，这行可避免 java_init.list 指向已混淆的失效类名）。
-adaptresourcefilecontents META-INF/xposed/java_init.list
-dontwarn io.github.libxposed.annotation.**

# 模块自身其余类。
# 放宽保留的原因：HOOK_TARGETS 自检清单里以字符串形式记录了 hook 名称，
# 这些名称会被打进日志与提示，字段/方法名被重命名会让日志对不上号。
-keep class com.dz.hmxs.** { *; }
-keepclassmembers class com.dz.hmxs.** { *; }

# ---------- 2. 其他 ----------
# 若后续重新引入 UI 或第三方库，其反射点（如 XML 标签名反序列化）需在此补 keep。
-dontwarn org.jetbrains.annotations.**
