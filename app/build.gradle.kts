plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Ключи Telegram API только из окружения CI (GitHub Secrets APP_ID / APP_HASH), см. ADR-0003.
// Без них сборка проходит с пустыми значениями, а приложение показывает ошибку в рантайме.
val tdApiId: Int = providers.environmentVariable("APP_ID").orNull?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { it.toIntOrNull() ?: error("APP_ID должен быть числом") }
    ?: 0
val tdApiHash: String = providers.environmentVariable("APP_HASH").orNull?.trim().orEmpty()

// Клиентский конфиг Firebase для push — тоже только из GitHub Secrets (ADR-0005).
// Без него сборка проходит, push выключен.
fun fcm(name: String): String = providers.environmentVariable(name).orNull?.trim().orEmpty()

// Фиксированный debug-keystore из GitHub Secrets (ADR-0001): CI кладёт его в DEBUG_KEYSTORE_PATH.
// Без него AGP подписывает случайным ключом раннера, и APK не встанет поверх установленного.
val debugKeystorePath: String? = providers.environmentVariable("DEBUG_KEYSTORE_PATH").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.iufb.reach"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.iufb.reach"
        minSdk = 36
        targetSdk = 37
        versionCode = 6
        versionName = "0.5.0"

        buildConfigField("int", "TD_API_ID", tdApiId.toString())
        buildConfigField("String", "TD_API_HASH", "\"$tdApiHash\"")
        for (name in listOf("FCM_PROJECT_ID", "FCM_APP_ID", "FCM_API_KEY", "FCM_SENDER_ID")) {
            buildConfigField("String", name, "\"${fcm(name)}\"")
        }
    }

    signingConfigs {
        getByName("debug") {
            if (debugKeystorePath != null) {
                storeFile = file(debugKeystorePath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:td"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.coil.compose)
    implementation(libs.androidx.emoji2.bundled)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.lottie.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
}
