plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 個人設定はリポジトリ直下の `.env`(git 管理外)から読む。無ければ環境変数、
 * それも無ければ下の fallback。テンプレートは `.env.template`。
 *
 *   X3_HOST       X3 の IP / ホスト名 (既定 x3disp.local)
 *   X3_PORT       Wi-Fi のポート (既定 5283)
 *   X3_TRANSPORT  既定の接続方式 "ble" / "wifi" (既定 ble)
 */
fun readDotenv(): Map<String, String> {
    val file = rootProject.file(".env")
    if (!file.exists()) return emptyMap()
    val out = mutableMapOf<String, String>()
    file.readLines().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || !line.contains('=')) return@forEach
        val key = line.substringBefore('=').removePrefix("export ").trim()
        var value = line.substringAfter('=').trim()
        if (value.length >= 2 && value.first() == value.last() &&
            (value.first() == '"' || value.first() == '\'')
        ) {
            value = value.substring(1, value.length - 1)
        }
        if (key.isNotEmpty()) out[key] = value
    }
    return out
}

val dotenv = readDotenv()

fun setting(name: String, fallback: String): String {
    val fromEnv = System.getenv(name)
    if (!fromEnv.isNullOrBlank()) return fromEnv
    val fromFile = dotenv[name]
    if (!fromFile.isNullOrBlank()) return fromFile
    return fallback
}

/** 文字列リテラルへ埋め込むので引用符・バックスラッシュは落とす */
fun cString(value: String): String = value.replace("\\", "").replace("\"", "")

android {
    namespace = "net.x3dp"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.x3dp"
        minSdk = 30            // AccessibilityService.takeScreenshot は API 30+
        targetSdk = 34
        versionCode = 38
        versionName = "0.38"

        // ビルド時の既定値(アプリ内の設定画面でいつでも上書きできる)
        buildConfigField("String", "DEFAULT_HOST", "\"${cString(setting("X3_HOST", "x3disp.local"))}\"")
        buildConfigField("int", "DEFAULT_PORT", setting("X3_PORT", "5283"))
        buildConfigField("String", "DEFAULT_TRANSPORT", "\"${cString(setting("X3_TRANSPORT", "ble"))}\"")
    }

    buildFeatures {
        buildConfig = true
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
