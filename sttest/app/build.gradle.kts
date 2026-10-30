import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // 도우미(kr.daedeok.calls)와 다른 이름 → 따로 설치되고, 지워도 도우미에 영향 없음
    namespace = "kr.daedeok.sttest"
    compileSdk = 36
    defaultConfig {
        applicationId = "kr.daedeok.sttest"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.2-test"
    }
    signingConfigs {
        create("ddk") {
            storeFile = file("ddk-calls.keystore")
            storePassword = "daedeok-calls-2026"
            keyAlias = "ddk"
            keyPassword = "daedeok-calls-2026"
        }
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("ddk") }
        getByName("debug") { signingConfig = signingConfigs.getByName("ddk") }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
