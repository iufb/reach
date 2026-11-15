plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.iufb.reach.core.td"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }
}
