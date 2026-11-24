# librlottiejni.so: rlottie (Samsung) + lz4 статически и тонкий JNI-мост (rlottie_jni.cpp).
# Собирается только в GitHub Actions (.github/workflows/rlottie.yml, ADR-0013).
# Версии исходников — в RLOTTIE_COMMIT / LZ4_COMMIT, их передаёт workflow.
cmake_minimum_required(VERSION 3.22)
project(rlottiejni LANGUAGES C CXX ASM)

include(FetchContent)

set(LOTTIE_MODULE OFF CACHE BOOL "" FORCE) # без подгрузки image-loader через dlopen
set(LOTTIE_THREAD OFF CACHE BOOL "" FORCE) # рисуем синхронно в своих потоках
set(LOTTIE_TEST OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(rlottie
    GIT_REPOSITORY https://github.com/Samsung/rlottie.git
    GIT_TAG ${RLOTTIE_COMMIT}
)
FetchContent_Declare(lz4
    GIT_REPOSITORY https://github.com/lz4/lz4.git
    GIT_TAG ${LZ4_COMMIT}
)
FetchContent_Populate(rlottie)
FetchContent_Populate(lz4)

# Без LOTTIE_THREAD растеризатор rlottie — один статический объект на процесс, а мы рисуем
# из нескольких потоков (падало в SIGSEGV). Делаем его своим у каждого потока.
set(VRASTER ${rlottie_SOURCE_DIR}/src/vector/vraster.cpp)
file(READ ${VRASTER} VRASTER_SRC)
string(REPLACE
    "    static RleTaskScheduler &instance()\n    {\n        static RleTaskScheduler singleton;\n        return singleton;\n    }\n\n    void stop() {}"
    "    static RleTaskScheduler &instance()\n    {\n        static thread_local RleTaskScheduler singleton;\n        return singleton;\n    }\n\n    void stop() {}"
    VRASTER_PATCHED "${VRASTER_SRC}")
if(VRASTER_PATCHED STREQUAL VRASTER_SRC)
    message(FATAL_ERROR "vraster.cpp: не нашёл однопоточный RleTaskScheduler — обновите патч")
endif()
file(WRITE ${VRASTER} "${VRASTER_PATCHED}")

add_subdirectory(${rlottie_SOURCE_DIR} ${rlottie_BINARY_DIR})

# rlottie как статическая библиотека протаскивает свой version-script в того, кто её линкует,
# и он спрятал бы JNI-символы. Нам из её интерфейса ничего не нужно.
set_property(TARGET rlottie PROPERTY INTERFACE_LINK_LIBRARIES "")

add_library(rlottiejni SHARED rlottie_jni.cpp ${lz4_SOURCE_DIR}/lib/lz4.c)
target_include_directories(rlottiejni PRIVATE ${rlottie_SOURCE_DIR}/inc ${lz4_SOURCE_DIR}/lib)
target_compile_options(rlottiejni PRIVATE -O2 -fvisibility=hidden -fno-exceptions -fno-rtti)
target_link_libraries(rlottiejni PRIVATE rlottie jnigraphics log)
# Страницы 16 КБ (Android 15+)
target_link_options(rlottiejni PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections)
