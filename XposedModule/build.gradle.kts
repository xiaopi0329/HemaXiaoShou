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
        versionCode = 3
        versionName = "1.0.2"
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

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
