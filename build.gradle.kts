repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
    maven("https://dl.google.com/dl/android/maven2")
}

plugins {
    id("com.android.application") version "8.1.4" apply false
    id("com.android.library") version "8.1.4" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
}
