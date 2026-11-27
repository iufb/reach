# libtgcallsjni.so: tgcalls + WebRTC из дерева Telegram Android и свой тонкий JNI-мост (ADR-0015).
# Собирается только в GitHub Actions (.github/workflows/voip.yml). Workflow кладёт этот файл
# вместо TMessagesProj/jni/CMakeLists.txt зафиксированного коммита Telegram Android, а наши .cpp —
# в TMessagesProj/jni/reach/. Пути исходников в voip/CMakeLists.txt Telegram относительные, поэтому
# корень CMake-проекта — именно их каталог jni.
#
# Берём из Telegram: voip/CMakeLists.txt (цели rnnoise, tgcalls_tp, tgcalls, voipandroid), вендоренный
# WebRTC (voip/webrtc), их prebuild/lib/<abi>/*.a (BoringSSL, opus, libvpx, openh264, FFmpeg, dav1d,
# wasm-micro-runtime, tde2e) и подмодули с заголовками. Не берём: tgnet, их JNI-обвязку
# (org_telegram_messenger_voip_Instance.cpp завязана на их Java-классы) и всё не про звонки.
cmake_minimum_required(VERSION 3.22)
project(tgcallsjni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(${ANDROID_ABI} STREQUAL "armeabi-v7a" OR ${ANDROID_ABI} STREQUAL "arm64-v8a")
    enable_language(ASM) # voip/webrtc/.../complex_bit_reverse_arm.S
endif()

set(CAN_COMPILE_SME FALSE CACHE INTERNAL "Disable libyuv SME" FORCE)
add_subdirectory(third_party/libyuv ${CMAKE_CURRENT_BINARY_DIR}/libyuv EXCLUDE_FROM_ALL)

set(ABSL_PROPAGATE_CXX_STD ON)
set(ABSL_BUILD_TESTING OFF)
set(ABSL_ENABLE_INSTALL OFF)
add_subdirectory(third_party/absl ${CMAKE_CURRENT_BINARY_DIR}/absl EXCLUDE_FROM_ALL)

# Тот же набор, что у Telegram (их jni/CMakeLists.txt)
add_library(webrtc_absl INTERFACE)
target_link_libraries(webrtc_absl INTERFACE
    absl::base absl::strings absl::str_format absl::cord absl::synchronization absl::time
    absl::status absl::statusor absl::hash absl::flat_hash_map absl::flat_hash_set
    absl::flags absl::flags_parse absl::random_random absl::random_distributions
    absl::crc32c absl::log absl::check)

foreach(lib avutil avformat avcodec swresample swscale crypto ssl tde2e tdutils)
    add_library(${lib} STATIC IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/lib${lib}.a)
endforeach()
foreach(lib vpx dav1d opus)
    add_library(lib${lib} STATIC IMPORTED)
    set_target_properties(lib${lib} PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/lib${lib}.a)
endforeach()

# Платформенный код tgcalls ищет класс захвата видео Telegram по имени — у нас он в core:voip
set(VIDEO_CAPTURER_FROM "org/telegram/messenger/voip/VideoCapturerDevice")
set(VIDEO_CAPTURER_TO "io/github/iufb/reach/core/voip/VideoCapturerDevice")
foreach(file AndroidInterface.cpp AndroidContext.cpp VideoCameraCapturer.cpp)
    set(path ${CMAKE_HOME_DIRECTORY}/tgcalls/tgcalls/platform/android/${file})
    file(READ ${path} source)
    string(REPLACE "${VIDEO_CAPTURER_FROM}" "${VIDEO_CAPTURER_TO}" patched "${source}")
    string(REPLACE "Java_org_telegram_messenger_voip_VideoCapturerDevice_" "Java_io_github_iufb_reach_core_voip_VideoCapturerDevice_" patched "${patched}")
    if(patched STREQUAL source)
        message(FATAL_ERROR "${file}: не нашёл VideoCapturerDevice Telegram — обновите патч")
    endif()
    file(WRITE ${path} "${patched}")
endforeach()

# rnnoise, tgcalls_tp, tgcalls (+ WebRTC), voipandroid; ещё объявляет imported openh264 и wasm
include(${CMAKE_HOME_DIRECTORY}/voip/CMakeLists.txt)

# voip/CMakeLists.txt перезаписывает CMAKE_CXX_FLAGS на "-std=c++17 -DANDROID -g". Отладочная
# информация нам не нужна (.so всё равно режется strip), а секции — для --gc-sections.
set(CMAKE_CXX_FLAGS "-std=c++17 -DANDROID -ffunction-sections -fdata-sections")
set(CMAKE_C_FLAGS "-w -std=c11 -DANDROID -D_LARGEFILE_SOURCE=1 -Wno-error=implicit-function-declaration -ffunction-sections -fdata-sections")

# Их JNI-обвязка ссылается на org.telegram.* и tgnet — у нас свой мост
get_target_property(VOIPANDROID_SOURCES voipandroid SOURCES)
list(FILTER VOIPANDROID_SOURCES EXCLUDE REGEX "org_telegram_messenger_voip_Instance\\.cpp$")
set_target_properties(voipandroid PROPERTIES SOURCES "${VOIPANDROID_SOURCES}")

add_library(tgcallsjni SHARED
    reach/tgcalls_jni.cpp
    reach/FileLog.cpp) # пропатченный WebRTC Telegram пишет в tgnet/FileLog — своя реализация в logcat

target_link_options(tgcallsjni PRIVATE
    "-Wl,--gc-sections" "-Wl,--icf=safe" "-Wl,-Bsymbolic"
    "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

# Порядок как у Telegram: voipandroid целиком (JNI-функции org.webrtc.* понадобятся Java-части WebRTC)
target_link_libraries(tgcallsjni PRIVATE
    -Wl,--whole-archive rnnoise openh264 voipandroid -Wl,--no-whole-archive
    webrtc_absl
    tgcalls
    wasm
    tgcalls_tp
    avformat
    avcodec
    swresample
    swscale
    libvpx
    libdav1d
    libopus
    tde2e
    tdutils
    yuv
    avutil
    ssl
    crypto
    log
    z
    GLESv2
    EGL
    android
    OpenSLES
    jnigraphics
    cpufeatures)

include(AndroidNdkModules)
android_ndk_import_module_cpufeatures()
