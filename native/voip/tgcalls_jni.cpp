// JNI-мост к tgcalls (ADR-0015):
// - io.github.iufb.reach.core.voip.TgCalls — версии реализаций и max layer для callProtocol;
// - io.github.iufb.reach.core.voip.CallInstance — звонок один-на-один: создание из callStateReady,
//   сигнализация, микрофон, завершение. Колбэки tgcalls приходят из его потоков и уходят в Kotlin как есть.

#include <jni.h>

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "Instance.h"
#include "InstanceImpl.h"
#include "VideoCaptureInterface.h"
#include "tgcalls/v2/InstanceV2Impl.h"
#include "tgcalls/v2/InstanceV2ReferenceImpl.h"
#if defined(__aarch64__)
#include "v2wasm/InstanceV2PumpImpl.h"
#endif

#include "modules/utility/include/jvm_android.h"
#include "rtc_base/ssl_adapter.h"
#include "sdk/android/native_api/base/init.h"
#include "sdk/android/native_api/jni/jvm.h"

using namespace tgcalls;

namespace {

// Те же реализации, что регистрирует Telegram Android (org_telegram_messenger_voip_Instance.cpp)
const auto kRegisterV1 = Register<InstanceImpl>();
const auto kRegisterV2 = Register<InstanceV2Impl>();
const auto kRegisterV2Reference = Register<InstanceV2ReferenceImpl>();
#if defined(__aarch64__)
const auto kRegisterV2Pump = Register<InstanceV2PumpImpl>();
#endif

// Методы CallInstance, которые зовёт натив. Имена держит consumer-правило R8 модуля core:voip.
struct PeerMethods {
    jmethodID onState = nullptr;
    jmethodID onSignalBars = nullptr;
    jmethodID onSignalingData = nullptr;
    jmethodID onAudioLevels = nullptr;
    jmethodID onRemoteMediaState = nullptr;
    jmethodID onStopped = nullptr;
};
PeerMethods gPeer;

struct Holder {
    std::unique_ptr<Instance> instance;
    jobject peer = nullptr; // global ref на CallInstance
    std::string persistentStatePath;
};

std::once_flag gWebrtcOnce;

void initWebrtc(JNIEnv *env) {
    std::call_once(gWebrtcOnce, [env] {
        JavaVM *vm = nullptr;
        env->GetJavaVM(&vm);
        webrtc::InitAndroid(vm);       // глобальный JavaVM + загрузчик классов приложения для org.webrtc.*
        webrtc::JVM::Initialize(vm);   // классы org.webrtc.voiceengine.* для аудио
        rtc::InitializeSSL();
    });
}

std::string toStd(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<uint8_t> toBytes(JNIEnv *env, jbyteArray value) {
    if (value == nullptr) return {};
    std::vector<uint8_t> result(env->GetArrayLength(value));
    env->GetByteArrayRegion(value, 0, static_cast<jsize>(result.size()), reinterpret_cast<jbyte *>(result.data()));
    return result;
}

jbyteArray toJava(JNIEnv *env, const std::vector<uint8_t> &value) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    return result;
}

std::vector<uint8_t> readFile(const std::string &path) {
    std::vector<uint8_t> result;
    FILE *file = fopen(path.c_str(), "rb");
    if (!file) return result;
    fseek(file, 0, SEEK_END);
    long size = ftell(file);
    fseek(file, 0, SEEK_SET);
    if (size > 0 && size < 512 * 1024) {
        result.resize(static_cast<size_t>(size));
        if (fread(result.data(), 1, result.size(), file) != result.size()) result.clear();
    }
    fclose(file);
    return result;
}

void writeFile(const std::string &path, const std::vector<uint8_t> &value) {
    FILE *file = fopen(path.c_str(), "wb");
    if (!file) return;
    fwrite(value.data(), 1, value.size(), file);
    fclose(file);
}

// Колбэк в Kotlin из любого потока tgcalls
template <typename F>
void withEnv(F &&f) {
    JNIEnv *env = webrtc::AttachCurrentThreadIfNeeded();
    f(env);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

struct ServerFields {
    jfieldID id, ipv4, ipv6, port, peerTag, isTcp, isWebrtc, username, password, supportsTurn, supportsStun;
};

ServerFields serverFields(JNIEnv *env, jclass clazz) {
    return {
        env->GetFieldID(clazz, "id", "J"),
        env->GetFieldID(clazz, "ipv4", "Ljava/lang/String;"),
        env->GetFieldID(clazz, "ipv6", "Ljava/lang/String;"),
        env->GetFieldID(clazz, "port", "I"),
        env->GetFieldID(clazz, "peerTag", "[B"),
        env->GetFieldID(clazz, "isTcp", "Z"),
        env->GetFieldID(clazz, "isWebrtc", "Z"),
        env->GetFieldID(clazz, "username", "Ljava/lang/String;"),
        env->GetFieldID(clazz, "password", "Ljava/lang/String;"),
        env->GetFieldID(clazz, "supportsTurn", "Z"),
        env->GetFieldID(clazz, "supportsStun", "Z"),
    };
}

// Как Telegram Android: сервер с IPv6 добавляется дважды — по v4 и по v6
void pushRtcServer(std::vector<RtcServer> &servers, RtcServer server, const std::string &ipv6) {
    if (!ipv6.empty()) {
        RtcServer v6 = server;
        v6.host = ipv6;
        servers.push_back(std::move(server));
        servers.push_back(std::move(v6));
    } else {
        servers.push_back(std::move(server));
    }
}

int stateCode(State state) {
    switch (state) {
        case State::WaitInit: return 1;
        case State::WaitInitAck: return 2;
        case State::Established: return 3;
        case State::Failed: return 4;
        case State::Reconnecting: return 5;
    }
    return 0;
}

Holder *holder(jlong ptr) { return reinterpret_cast<Holder *>(ptr); }

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

/**
 * servers — массив CallInstance.Server, порядок как в callStateReady. Реле Telegram нумеруются 1..N
 * по возрастанию id (reflectorId, как у Telegram Android), WebRTC-серверы — 0.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_iufb_reach_core_voip_CallInstance_nativeCreate(
    JNIEnv *env, jclass clazz, jobject peer,
    jstring version, jdouble initializationTimeout, jdouble receiveTimeout,
    jboolean enableP2p, jint maxLayer, jstring customParameters,
    jobjectArray servers, jbyteArray encryptionKey, jboolean isOutgoing,
    jstring persistentStatePath, jstring logPath, jint networkType, jboolean muted) {
    initWebrtc(env);

    if (gPeer.onState == nullptr) {
        gPeer.onState = env->GetMethodID(clazz, "onState", "(I)V");
        gPeer.onSignalBars = env->GetMethodID(clazz, "onSignalBars", "(I)V");
        gPeer.onSignalingData = env->GetMethodID(clazz, "onSignalingData", "([B)V");
        gPeer.onAudioLevels = env->GetMethodID(clazz, "onAudioLevels", "(FF)V");
        gPeer.onRemoteMediaState = env->GetMethodID(clazz, "onRemoteMediaState", "(II)V");
        gPeer.onStopped = env->GetMethodID(clazz, "onStopped", "(Ljava/lang/String;)V");
    }

    auto *h = new Holder;
    h->peer = env->NewGlobalRef(peer);
    h->persistentStatePath = toStd(env, persistentStatePath);
    jobject peerRef = h->peer;

    auto key = std::make_shared<std::array<uint8_t, EncryptionKey::kSize>>();
    const std::vector<uint8_t> keyBytes = toBytes(env, encryptionKey);
    if (keyBytes.size() != EncryptionKey::kSize) {
        env->DeleteGlobalRef(h->peer);
        delete h;
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "encryption key must be 256 bytes");
        return 0;
    }
    memcpy(key->data(), keyBytes.data(), keyBytes.size());

    const std::string log = toStd(env, logPath);
    Descriptor descriptor = {
        .config = Config{
            .initializationTimeout = initializationTimeout,
            .receiveTimeout = receiveTimeout,
            .dataSaving = DataSaving::Never,
            .enableP2P = enableP2p == JNI_TRUE,
            .enableStunMarking = false,
            .enableAEC = true,
            .enableNS = true,
            .enableAGC = true,
            .enableVolumeControl = true,
            .logPath = {log},
            .statsLogPath = {log.empty() ? std::string() : log + ".stats"},
            .maxApiLayer = maxLayer,
            .enableHighBitrateVideo = true,
            .customParameters = toStd(env, customParameters),
        },
        .encryptionKey = EncryptionKey(std::move(key), isOutgoing == JNI_TRUE),
        .stateUpdated = [peerRef](State state) {
            withEnv([&](JNIEnv *e) { e->CallVoidMethod(peerRef, gPeer.onState, stateCode(state)); });
        },
        .signalBarsUpdated = [peerRef](int count) {
            withEnv([&](JNIEnv *e) { e->CallVoidMethod(peerRef, gPeer.onSignalBars, count); });
        },
        .audioLevelsUpdated = [peerRef](float mine, float remote) {
            withEnv([&](JNIEnv *e) { e->CallVoidMethod(peerRef, gPeer.onAudioLevels, mine, remote); });
        },
        .remoteMediaStateUpdated = [peerRef](AudioState audio, VideoState video) {
            withEnv([&](JNIEnv *e) {
                e->CallVoidMethod(peerRef, gPeer.onRemoteMediaState, static_cast<jint>(audio), static_cast<jint>(video));
            });
        },
        .signalingDataEmitted = [peerRef](const std::vector<uint8_t> &data) {
            withEnv([&](JNIEnv *e) {
                jbyteArray array = toJava(e, data);
                e->CallVoidMethod(peerRef, gPeer.onSignalingData, array);
                e->DeleteLocalRef(array);
            });
        },
    };
    descriptor.version = toStd(env, version);
    descriptor.persistentState.value = readFile(h->persistentStatePath);

    const jsize count = env->GetArrayLength(servers);
    std::vector<int64_t> reflectorIds;
    for (jsize i = 0; i < count; i++) {
        jobject server = env->GetObjectArrayElement(servers, i);
        ServerFields f = serverFields(env, env->GetObjectClass(server));
        if (!env->GetBooleanField(server, f.isWebrtc)) reflectorIds.push_back(env->GetLongField(server, f.id));
        env->DeleteLocalRef(server);
    }
    std::sort(reflectorIds.begin(), reflectorIds.end());

    for (jsize i = 0; i < count; i++) {
        jobject server = env->GetObjectArrayElement(servers, i);
        ServerFields f = serverFields(env, env->GetObjectClass(server));
        const int64_t id = env->GetLongField(server, f.id);
        const std::string ipv4 = toStd(env, (jstring) env->GetObjectField(server, f.ipv4));
        const std::string ipv6 = toStd(env, (jstring) env->GetObjectField(server, f.ipv6));
        const auto port = static_cast<uint16_t>(env->GetIntField(server, f.port));

        RtcServer rtc;
        rtc.host = ipv4;
        rtc.port = port;
        if (env->GetBooleanField(server, f.isWebrtc)) {
            rtc.login = toStd(env, (jstring) env->GetObjectField(server, f.username));
            rtc.password = toStd(env, (jstring) env->GetObjectField(server, f.password));
            rtc.isTurn = env->GetBooleanField(server, f.supportsTurn) == JNI_TRUE;
            pushRtcServer(descriptor.rtcServers, rtc, ipv6);
        } else {
            const auto index = std::lower_bound(reflectorIds.begin(), reflectorIds.end(), id) - reflectorIds.begin();
            rtc.id = static_cast<uint8_t>(index + 1);
            rtc.isTurn = true;
            rtc.isTcp = env->GetBooleanField(server, f.isTcp) == JNI_TRUE;
            pushRtcServer(descriptor.rtcServers, rtc, ipv6);

            Endpoint endpoint;
            endpoint.endpointId = id;
            endpoint.host = EndpointHost{ipv4, ipv6};
            endpoint.port = port;
            endpoint.type = EndpointType::UdpRelay;
            const std::vector<uint8_t> tag = toBytes(env, (jbyteArray) env->GetObjectField(server, f.peerTag));
            memcpy(endpoint.peerTag, tag.data(), std::min<size_t>(tag.size(), sizeof(endpoint.peerTag)));
            descriptor.endpoints.push_back(std::move(endpoint));
        }
        env->DeleteLocalRef(server);
    }

    h->instance = Meta::Create(descriptor.version, std::move(descriptor));
    if (!h->instance) {
        env->DeleteGlobalRef(h->peer);
        delete h;
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "tgcalls: unsupported version");
        return 0;
    }
    h->instance->setNetworkType(static_cast<NetworkType>(networkType));
    h->instance->setMuteMicrophone(muted == JNI_TRUE);
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_iufb_reach_core_voip_CallInstance_nativeReceiveSignalingData(JNIEnv *env, jclass, jlong ptr, jbyteArray data) {
    holder(ptr)->instance->receiveSignalingData(toBytes(env, data));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_iufb_reach_core_voip_CallInstance_nativeSetMuted(JNIEnv *, jclass, jlong ptr, jboolean muted) {
    holder(ptr)->instance->setMuteMicrophone(muted == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_iufb_reach_core_voip_CallInstance_nativeSetNetworkType(JNIEnv *, jclass, jlong ptr, jint type) {
    holder(ptr)->instance->setNetworkType(static_cast<NetworkType>(type));
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_iufb_reach_core_voip_CallInstance_nativePreferredRelayId(JNIEnv *, jclass, jlong ptr) {
    return static_cast<jlong>(holder(ptr)->instance->getPreferredRelayId());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_iufb_reach_core_voip_CallInstance_nativeLastError(JNIEnv *env, jclass, jlong ptr) {
    return env->NewStringUTF(holder(ptr)->instance->getLastError().c_str());
}

/** Завершает звонок. После onStopped указатель недействителен: Holder удаляется в колбэке. */
extern "C" JNIEXPORT void JNICALL
Java_io_github_iufb_reach_core_voip_CallInstance_nativeStop(JNIEnv *, jclass, jlong ptr) {
    Holder *h = holder(ptr);
    h->instance->stop([h](const FinalState &state) {
        writeFile(h->persistentStatePath, state.persistentState.value);
        withEnv([&](JNIEnv *e) {
            jstring log = e->NewStringUTF(state.debugLog.c_str());
            e->CallVoidMethod(h->peer, gPeer.onStopped, log);
            e->DeleteLocalRef(log);
            e->DeleteGlobalRef(h->peer);
        });
        delete h;
    });
}
