import java.net.URI
import java.util.zip.ZipInputStream

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

dependencies {
    // TdApi.java использует @IntDef / @Nullable
    implementation(libs.androidx.annotation)
}

/**
 * Скачивает tdlib.zip из GitHub Release и раскладывает его:
 * Java-исходники (TdApi, Client) в [javaDir], .so по ABI в [jniLibsDir].
 * Версия задаётся свойством `tdlib.release` в gradle.properties (ADR-0002).
 */
abstract class FetchTdlibTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:OutputDirectory
    abstract val javaDir: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibsDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val java = javaDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val jni = jniLibsDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        var files = 0
        ZipInputStream(URI(url.get()).toURL().openStream().buffered()).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { entry ->
                // Внутри архива: tdlib/java/..., tdlib/libs/<abi>/libtdjni.so, tdlib/javadoc/...
                val target = when {
                    entry.name.startsWith("tdlib/java/") -> java.resolve(entry.name.removePrefix("tdlib/java/"))
                    entry.name.startsWith("tdlib/libs/") -> jni.resolve(entry.name.removePrefix("tdlib/libs/"))
                    else -> return@forEach
                }
                check(target.normalize().startsWith(java) || target.normalize().startsWith(jni)) { "Подозрительный путь в архиве: ${entry.name}" }
                target.parentFile.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
                files++
            }
        }
        check(java.resolve("org/drinkless/tdlib/TdApi.java").isFile) { "В tdlib.zip нет TdApi.java" }
        check(files > 0)
    }
}

val tdlibRelease = providers.gradleProperty("tdlib.release")

val fetchTdlib = tasks.register<FetchTdlibTask>("fetchTdlib") {
    group = "tdlib"
    description = "Скачивает и распаковывает TDLib из GitHub Release"
    url = tdlibRelease.map { "https://github.com/iufb/reach/releases/download/$it/tdlib.zip" }
    javaDir = layout.buildDirectory.dir("tdlib/java")
    jniLibsDir = layout.buildDirectory.dir("tdlib/jniLibs")
}

// Подключаем как сгенерированные исходники: таск выполнится перед компиляцией
// и при Gradle sync в Android Studio (нужно для автодополнения TdApi).
androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(fetchTdlib, FetchTdlibTask::javaDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(fetchTdlib, FetchTdlibTask::jniLibsDir)
    }
}
