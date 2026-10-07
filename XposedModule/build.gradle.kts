plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

repositories {
    mavenCentral()
    google()
}

android {
    namespace = "com.dz.hmxs"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dz.hmxs"
        minSdk = 21
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 开 R8：整个 androidx 全家桶只保留真正用到的类与方法，
            // dex 从 ~9 MB 降到 ~1.6 MB；shrinkResources 同步删掉未使用资源。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // DexKit 是 C++ 实现（libdexkit.so）。minSdk < 23 时若不开启 useLegacyPackaging，
    // 打包时 so 会被压缩进 APK，运行时 System.loadLibrary("dexkit") 将抛
    // java.lang.UnsatisfiedLinkError: couldn't find "libdexkit.so"。
    // 本项目 minSdk = 21，因此必须开启；附带好处是 so 以压缩形式存储，APK 更小。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // 本模块没有 UI（设置页已移除），不需要任何 androidx 库；
    // 全部 API 都来自 Android framework（Context / Handler / Toast 等）。
    // 仅保留 libxposed API 作为 compileOnly（由框架在运行时提供，不打进 APK）。
    compileOnly("io.github.libxposed:api:102.0.0")

    // DexKit：C++ 实现的运行时 dex 解析库，按字符串特征反查被 R8 混淆后的类名。
    // 宿主更新导致混淆名变化时，用它自动定位新类名，免去人工重新对照 smali。
    implementation("org.luckypray:dexkit:2.3.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
