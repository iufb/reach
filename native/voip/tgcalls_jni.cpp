// JNI-мост к tgcalls для io.github.iufb.reach.core.voip.TgCalls (ADR-0015).
// Пока только метаданные для callProtocol: версии реализаций и max layer. Сам звонок — #409.

#include <jni.h>

#include <string>
#include <vector>

#include "Instance.h"
#include "InstanceImpl.h"
#include "tgcalls/v2/InstanceV2Impl.h"
#include "tgcalls/v2/InstanceV2ReferenceImpl.h"
#if defined(__aarch64__)
#include "v2wasm/InstanceV2PumpImpl.h"
#endif

using namespace tgcalls;

namespace {

// Те же реализации, что регистрирует Telegram Android (org_telegram_messenger_voip_Instance.cpp)
const auto kRegisterV1 = Register<InstanceImpl>();
const auto kRegisterV2 = Register<InstanceV2Impl>();
const auto kRegisterV2Reference = Register<InstanceV2ReferenceImpl>();
#if defined(__aarch64__)
const auto kRegisterV2Pump = Register<InstanceV2PumpImpl>();
#endif

} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_iufb_reach_core_voip_TgCalls_nativeVersions(JNIEnv *env, jclass) {
    const std::vector<std::string> versions = Meta::Versions();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(versions.size()), stringClass, nullptr);
    for (size_t i = 0; i < versions.size(); ++i) {
        jstring value = env->NewStringUTF(versions[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_iufb_reach_core_voip_TgCalls_nativeMaxLayer(JNIEnv *, jclass) {
    return Meta::MaxLayer();
}
