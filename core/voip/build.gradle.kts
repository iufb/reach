import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.iufb.reach.core.voip"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // Java-часть WebRTC (org.webrtc.*, scripts/vendor-webrtc-java.sh) использует @Nullable / @Keep
    implementation(libs.androidx.annotation)
}

/**
 * Скачивает voip.zip из GitHub Release (workflow voip.yml) и раскладывает .so по ABI
 * в [jniLibsDir]. Версия — свойство `voip.release` в gradle.properties (ADR-0015).
 */
abstract class FetchVoipTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:OutputDirectory
    abstract val jniLibsDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val jni = jniLibsDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        var files = 0
        ZipInputStream(URI(url.get()).toURL().openStream().buffered()).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { entry ->
                // Внутри архива: voip/libs/<abi>/libtgcallsjni.so
                if (!entry.name.startsWith("voip/libs/")) return@forEach
                val target = jni.resolve(entry.name.removePrefix("voip/libs/"))
                check(target.normalize().startsWith(jni)) { "Подозрительный путь в архиве: ${entry.name}" }
                target.parentFile.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
                files++
            }
        }
        check(files > 0) { "В voip.zip нет библиотек" }
    }
}

val voipRelease = providers.gradleProperty("voip.release")

val fetchVoip = tasks.register<FetchVoipTask>("fetchVoip") {
    group = "voip"
    description = "Скачивает libtgcallsjni.so из GitHub Release"
    url = voipRelease.map { "https://github.com/iufb/reach/releases/download/$it/voip.zip" }
    jniLibsDir = layout.buildDirectory.dir("voip/jniLibs")
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(fetchVoip, FetchVoipTask::jniLibsDir)
    }
}
