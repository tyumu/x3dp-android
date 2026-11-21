plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.x3dp"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.x3dp"
        minSdk = 30            // AccessibilityService.takeScreenshot は API 30+
        targetSdk = 34
        versionCode = 37
        versionName = "0.37"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // サイドロード用: デバッグ鍵で署名してreleaseビルド(小さいAPK)を作る
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    // Shizuku: shell権限で am start --windowingMode 5 / am task resize を実行するため
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
