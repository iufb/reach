// JNI-мост к rlottie для io.github.iufb.reach.core.rlottie.RLottie (ADR-0013).
// Меняете сигнатуры — поднимите JNI_VERSION в .github/workflows/rlottie.yml и rlottie.release.

#include <jni.h>
#include <android/bitmap.h>
#include <rlottie.h>
#include <lz4.h>
#include <cstring>
#include <memory>
#include <string>

namespace {

struct Handle {
    std::unique_ptr<rlottie::Animation> animation;
};

Handle *handle(jlong ptr) { return reinterpret_cast<Handle *>(ptr); }

/** Пиксели битмапа ARGB_8888 под замком; при ошибке pixels == nullptr. */
struct Pixels {
    JNIEnv *env;
    jobject bitmap;
    AndroidBitmapInfo info{};
    void *pixels = nullptr;

    Pixels(JNIEnv *e, jobject b) : env(e), bitmap(b) {
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) pixels = nullptr;
    }
    ~Pixels() {
        if (pixels) AndroidBitmap_unlockPixels(env, bitmap);
    }
    size_t bytes() const { return size_t(info.stride) * info.height; }
};

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *, void *) {
    // Разобранные модели rlottie кеширует по ключу (uniqueId файла): повторный create — без разбора JSON
    rlottie::configureModelCacheSize(64);
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_io_github_iufb_reach_core_rlottie_RLottie_nativeCreate(JNIEnv *env, jclass, jstring json, jstring key) {
    const char *jsonChars = env->GetStringUTFChars(json, nullptr);
    const char *keyChars = env->GetStringUTFChars(key, nullptr);
    std::string data(jsonChars);
    std::string cacheKey(keyChars);
    env->ReleaseStringUTFChars(json, jsonChars);
    env->ReleaseStringUTFChars(key, keyChars);
    auto animation = rlottie::Animation::loadFromData(std::move(data), cacheKey, "", !cacheKey.empty());
    if (!animation) return 0;
    auto *h = new Handle{std::move(animation)};
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_io_github_iufb_reach_core_rlottie_RLottie_nativeDestroy(JNIEnv *, jclass, jlong ptr) {
    delete handle(ptr);
}

JNIEXPORT jint JNICALL
Java_io_github_iufb_reach_core_rlottie_RLottie_nativeFrameCount(JNIEnv *, jclass, jlong ptr) {
    return jint(handle(ptr)->animation->totalFrame());
}

JNIEXPORT jdouble JNICALL
Java_io_github_iufb_reach_core_rlottie_RLottie_nativeFrameRate(JNIEnv *, jclass, jlong ptr) {
    return handle(ptr)->animation->frameRate();
}

/**
 * Кадр [frame] в [bitmap] (ARGB_8888) по его размеру. rlottie пишет ARGB32 premultiplied
 * (в памяти B, G, R, A), Android ждёт R, G, B, A — меняем R и B местами.
 * Тот же кадр подряд rlottie не перерисовывает: вызывающий так не делает.
 */
JNIEXPORT jboolean JNICALL
Java_io_github_iufb_reach_core_rlottie_RLottie_nativeRender(JNIEnv *env, jclass, jlong ptr, jint frame, jobject bitmap) {
    Pixels p(env, bitmap);
    if (!p.pixels) return JNI_FALSE;
    memset(p.pixels, 0, p.bytes());
    auto *buffer = static_cast<uint32_t *>(p.pixels);
    rlottie::Surface surface(buffer, p.info.width, p.info.height, p.info.stride);
    handle(ptr)->animation->renderSync(size_t(frame), surface);
    for (uint32_t y = 0; y < p.info.height; y++) {
        uint32_t *row = reinterpret_cast<uint32_t *>(static_cast<uint8_t *>(p.pixels) + size_t(y) * p.info.stride);
        for (uint32_t x = 0; x < p.info.width; x++) {
            uint32_t c = row[x];
            row[x] = (c & 0xFF00FF00u) | ((c >> 16) & 0xFFu) | ((c & 0xFFu) << 16);
        }
    }
    return JNI_TRUE;
}

/** Пиксели [bitmap], сжатые LZ4; `null` — не вышло. */
JNIEXPORT jbyteArray JNICALL
Java_io_github_iufb_reach_core_rlottie_RLottie_nativeCompress(JNIEnv *env, jclass, jobject bitmap) {
    std::unique_ptr<char[]> out;
    int size;
    {
        Pixels p(env, bitmap);
        if (!p.pixels) return nullptr;
        int srcSize = int(p.bytes());
        int bound = LZ4_compressBound(srcSize);
        out.reset(new char[bound]);
        size = LZ4_compress_default(static_cast<const char *>(p.pixels), out.get(), srcSize, bound);
    }
    if (size <= 0) return nullptr;
    jbyteArray result = env->NewByteArray(size);
    if (result) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte *>(out.get()));
    return result;
}

/** Обратно в [bitmap] того же размера, что и при сжатии. */
JNIEXPORT jboolean JNICALL
Java_io_github_iufb_reach_core_rlottie_RLottie_nativeDecompress(JNIEnv *env, jclass, jbyteArray data, jobject bitmap) {
    Pixels p(env, bitmap);
    if (!p.pixels) return JNI_FALSE;
    jsize size = env->GetArrayLength(data);
    jbyte *src = env->GetByteArrayElements(data, nullptr);
    int written = LZ4_decompress_safe(reinterpret_cast<const char *>(src), static_cast<char *>(p.pixels), size, int(p.bytes()));
    env->ReleaseByteArrayElements(data, src, JNI_ABORT);
    return written == int(p.bytes()) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
