import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // 도우미 1.9와 같은 이름·같은 열쇠 → 지우지 않고 덮어 설치됨 (설정·차단 목록 그대로)
    namespace = "kr.daedeok.calls"
    compileSdk = 36
    defaultConfig {
        applicationId = "kr.daedeok.calls"
        minSdk = 31          // 폰 안 음성인식 부품이 안드로이드 12 이상 필요
        targetSdk = 34       // 1.9와 같게 (알림·권한 동작이 바뀌지 않게)
        versionCode = 11
        versionName = "2.0"
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
    // 음성시험 0.2에서 폴드7에 맞는 것을 확인한 것과 같은 부품
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
