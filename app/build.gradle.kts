plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
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

// Release-ключ (ADR-0014): только в workflow Release по тегу v*, из секретов RELEASE_*.
// Без него release собирается неподписанным — обычный CI и PR из форков не ломаются.
fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
val releaseKeystorePath: String? = env("RELEASE_KEYSTORE_PATH")

android {
    namespace = "io.github.iufb.reach"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.iufb.reach"
        minSdk = 31
        targetSdk = 37
        versionCode = 14
        versionName = "0.13.0"

        buildConfigField("int", "TD_API_ID", tdApiId.toString())
        buildConfigField("String", "TD_API_HASH", "\"$tdApiHash\"")
        for (name in listOf("FCM_PROJECT_ID", "FCM_APP_ID", "FCM_API_KEY", "FCM_SENDER_ID")) {
            buildConfigField("String", name, "\"${fcm(name)}\"")
        }
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storeType = "PKCS12"
                storePassword = env("RELEASE_KEYSTORE_PASSWORD") ?: error("нет RELEASE_KEYSTORE_PASSWORD")
                keyAlias = env("RELEASE_KEY_ALIAS") ?: error("нет RELEASE_KEY_ALIAS")
                keyPassword = env("RELEASE_KEY_PASSWORD") ?: error("нет RELEASE_KEY_PASSWORD")
            }
        }
        getByName("debug") {
            if (debugKeystorePath != null) {
                storeFile = file(debugKeystorePath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        // Debug ставится рядом с release (ADR-0014): свой id, «Reach Dev». Push выключен —
        // FCM-проект привязан к основному applicationId
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "USE_TEST_DC", "false")
            for (name in listOf("FCM_PROJECT_ID", "FCM_APP_ID", "FCM_API_KEY", "FCM_SENDER_ID")) {
                buildConfigField("String", name, "\"\"")
            }
        }
        getByName("release") {
            // R8 и сжатие ресурсов (ADR-0014); keep-правила для JNI — в proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            buildConfigField("boolean", "USE_TEST_DC", "false")
        }
        // Debug-сборка на тестовых серверах Telegram (test DC): ставится рядом с основной,
        // своя база TDLib (отдельный applicationId → свой filesDir). Push выключен: FCM-проект
        // привязан к основному applicationId, а в test DC push всё равно не проверяем.
        create("dctest") { // имя build type не может начинаться с "test"
            initWith(getByName("debug"))
            applicationIdSuffix = ".testdc"
            versionNameSuffix = "-testdc"
            matchingFallbacks += "debug"
            buildConfigField("boolean", "USE_TEST_DC", "true")
            for (name in listOf("FCM_PROJECT_ID", "FCM_APP_ID", "FCM_API_KEY", "FCM_SENDER_ID")) {
                buildConfigField("String", name, "\"\"")
            }
        }
        // Release (R8) на тестовых серверах: ловит поломки R8 до релиза, агент проверяет её на устройстве
        // (ADR-0014). Тот же id и debug-ключ, что у dctest, — ставится поверх неё и сохраняет вход.
        create("releaseTestdc") {
            initWith(getByName("release"))
            applicationIdSuffix = ".testdc"
            versionNameSuffix = "-testdc"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("boolean", "USE_TEST_DC", "true")
            for (name in listOf("FCM_PROJECT_ID", "FCM_APP_ID", "FCM_API_KEY", "FCM_SENDER_ID")) {
                buildConfigField("String", name, "\"\"")
            }
        }
    }

    // Имя «Reach Test» и оранжевая иконка — общие у обеих сборок test DC
    sourceSets {
        getByName("releaseTestdc").res.srcDirs("src/dctest/res")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Lint в CI ловит вызовы API новее minSdk (ADR-0004) и строки, которых нет на одном из языков
    // интерфейса (#405). Остальные проверки пока выключены.
    lint {
        checkOnly += setOf("NewApi", "InlinedApi", "ObsoleteSdkInt", "MissingTranslation", "ExtraTranslation", "MissingQuantity")
        error += setOf("NewApi", "InlinedApi", "MissingTranslation", "ExtraTranslation", "MissingQuantity")
        warning += "ObsoleteSdkInt"
        abortOnError = true
        checkDependencies = true
    }
}

dependencies {
    implementation(project(":core:td"))
    implementation(project(":core:voip"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.telecom)
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
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.video)
    implementation(libs.camera.view)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)

    // Baseline profile (ADR-0014): генератор — модуль :baselineprofile, профиль — в src/main/generated
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}

baselineProfile {
    // Один профиль на release и releaseTestdc; генерируется вручную на устройстве, не при каждой сборке
    mergeIntoMain = true
    automaticGenerationDuringBuild = false
}
