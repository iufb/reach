// Генератор baseline profile (ADR-0014). Сборка — в CI, запуск — на устройстве с входом в test DC:
// scripts/baseline-profile.sh ставит APK, гоняет генератор через am instrument и забирает профиль.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "io.github.iufb.reach.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // Плагин заводит nonMinified/benchmark-варианты только для типов сборки, которые есть и здесь:
    // нужен releaseTestdc (test DC), а не release (боевые серверы)
    buildTypes {
        create("releaseTestdc") {
            matchingFallbacks += "release"
        }
    }
}

// Профиль снимаем на физическом устройстве, не на управляемом эмуляторе
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
