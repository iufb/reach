// Своя реализация tgnet/FileLog.h: пропатченный WebRTC Telegram Android логирует через неё,
// а сам tgnet (их сетевой стек) нам не нужен. Пишем в logcat с тегом "tgcalls".
// e / w / fatal — всегда, d / ref / delref — только при LOGS_ENABLED.

#include <android/log.h>

#include <cstdarg>
#include <string>

#include "tgnet/FileLog.h"

bool LOGS_ENABLED = false;

namespace {

void write(int priority, const char *message, va_list args) {
    __android_log_vprint(priority, "tgcalls", message, args);
}

} // namespace

FileLog::FileLog() {
    pthread_mutex_init(&mutex, nullptr);
}

void FileLog::init(std::string) {}

FileLog &FileLog::getInstance() {
    static FileLog instance;
    return instance;
}

#define FILELOG_WRITE(priority)            \
    va_list args;                          \
    va_start(args, message);               \
    write(priority, message, args);        \
    va_end(args)

void FileLog::fatal(const char *message, ...) { FILELOG_WRITE(ANDROID_LOG_FATAL); }

void FileLog::e(const char *message, ...) { FILELOG_WRITE(ANDROID_LOG_ERROR); }

void FileLog::w(const char *message, ...) { FILELOG_WRITE(ANDROID_LOG_WARN); }

void FileLog::d(const char *message, ...) {
    if (!LOGS_ENABLED) return;
    FILELOG_WRITE(ANDROID_LOG_DEBUG);
}

void FileLog::ref(const char *message, ...) {
    if (!LOGS_ENABLED) return;
    FILELOG_WRITE(ANDROID_LOG_VERBOSE);
}

void FileLog::delref(const char *message, ...) {
    if (!LOGS_ENABLED) return;
    FILELOG_WRITE(ANDROID_LOG_VERBOSE);
}
