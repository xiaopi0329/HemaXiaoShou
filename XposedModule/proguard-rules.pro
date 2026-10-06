# ProGuard rules for Xposed module
-keep class com.dz.hmxs.** { *; }
-keepclassmembers class com.dz.hmxs.** { *; }

# Modern Xposed API 102 entry point.
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
