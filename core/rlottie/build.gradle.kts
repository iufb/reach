import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.iufb.reach.core.rlottie"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }
}

/**
 * Скачивает rlottie.zip из GitHub Release (workflow rlottie.yml) и раскладывает .so по ABI
 * в [jniLibsDir]. Версия — свойство `rlottie.release` в gradle.properties (ADR-0013).
 */
abstract class FetchRlottieTask : DefaultTask() {
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
                // Внутри архива: rlottie/libs/<abi>/librlottiejni.so
                if (!entry.name.startsWith("rlottie/libs/")) return@forEach
                val target = jni.resolve(entry.name.removePrefix("rlottie/libs/"))
                check(target.normalize().startsWith(jni)) { "Подозрительный путь в архиве: ${entry.name}" }
                target.parentFile.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
                files++
            }
        }
        check(files > 0) { "В rlottie.zip нет библиотек" }
    }
}

val rlottieRelease = providers.gradleProperty("rlottie.release")

val fetchRlottie = tasks.register<FetchRlottieTask>("fetchRlottie") {
    group = "rlottie"
    description = "Скачивает librlottiejni.so из GitHub Release"
    url = rlottieRelease.map { "https://github.com/iufb/reach/releases/download/$it/rlottie.zip" }
    jniLibsDir = layout.buildDirectory.dir("rlottie/jniLibs")
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(fetchRlottie, FetchRlottieTask::jniLibsDir)
    }
}
