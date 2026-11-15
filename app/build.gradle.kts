plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

android {
    namespace = "io.github.iufb.reach"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.iufb.reach"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"

        buildConfigField("int", "TD_API_ID", tdApiId.toString())
        buildConfigField("String", "TD_API_HASH", "\"$tdApiHash\"")
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

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
