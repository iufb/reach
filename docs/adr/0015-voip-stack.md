# 0015. VoIP-стек: tgcalls + WebRTC из дерева Telegram Android

- Статус: принято
- Дата: 2026-11-27

## Контекст

TDLib даёт для звонков только сигнализацию: `createCall` / `acceptCall` / `discardCall`, `updateCall` с ключом и серверами в `callStateReady`, `sendCallSignalingData` ↔ `updateNewCallSignalingData`. Сам звук и видео, шифрование медиа и транспорт до реле Telegram или P2P делает отдельная библиотека. У официальных клиентов это **tgcalls** (LGPL-3.0, `TelegramMessenger/tgcalls`) поверх своего форка WebRTC. Сборка — только в CI (ADR-0001), бинари — через GitHub Release (ADR-0002).

Рассмотренные варианты:

| Вариант | Почему нет / да |
|---|---|
| **tgcalls + WebRTC из `DrKLO/Telegram` (`TMessagesProj/jni`)** | **Выбран.** Единственная живая Android-сборка tgcalls: CMake-скрипт `voip/CMakeLists.txt`, вендоренный и пропатченный под tgcalls WebRTC (`voip/webrtc`), подмодуль tgcalls и готовые `prebuild/lib/<abi>/*.a`. Совместимость с другими клиентами — та же, что у Telegram Android. |
| Upstream WebRTC (GN, depot_tools) + tgcalls | tgcalls собирается только с патчами Telegram к WebRTC, а GN-сборка с `gclient sync` — десятки ГБ и часы на раннере. |
| `tg_owt` (WebRTC Telegram Desktop на CMake) | Нет Android-таргета, нет Java-части WebRTC. |
| `libtgvoip` | Заброшен, только аудио, в tgcalls давно заменён `InstanceImpl` / V2. Для видео (#411) всё равно нужен tgcalls. |
| Готовые артефакты (Maven / AAR) | Поддерживаемых нет: форки Telegram (в том числе Telegram-FOSS) собирают тот же `TMessagesProj/jni` у себя. |

## Решение

- **Источник** — один коммит `DrKLO/Telegram`, `TELEGRAM_COMMIT` в `.github/workflows/voip.yml`. С ним едут подмодуль tgcalls, WebRTC и prebuilt-библиотеки — версии согласованы самим Telegram. Сейчас это Telegram Android 13.2.2 (7303), tgcalls `545f5ae`.
- **Что берём:** их `voip/CMakeLists.txt` (цели `rnnoise`, `tgcalls_tp`, `tgcalls`, `voipandroid`), `voip/webrtc`, libyuv и Abseil из исходников, а из `prebuild/lib/<abi>` — BoringSSL, opus, libvpx, openh264, FFmpeg, dav1d, wasm-micro-runtime, tde2e/tdutils. Подмодули ставим только нужные (заголовки к `.a`), весь остальной `TMessagesProj` не нужен.
- **Чего не берём:** tgnet (их сетевой стек; у нас TDLib) и их JNI-обвязку `org_telegram_messenger_voip_Instance.cpp` (GPL-2.0, завязана на их Java-классы). Вместо неё — свой мост `native/voip/tgcalls_jni.cpp`, по образцу rlottie. Пропатченный WebRTC Telegram пишет логи в `tgnet/FileLog`, его заменяет `native/voip/FileLog.cpp` (logcat, тег `tgcalls`).
- **Сборка:** `native/voip/CMakeLists.txt` подменяет их `jni/CMakeLists.txt` (пути исходников в `voip/CMakeLists.txt` относительные), цель — `libtgcallsjni.so`. NDK 27.2.12479018 и CMake 3.22.1 из SDK — как в `TMessagesProj/build.gradle`: `.a` собраны этим NDK, а в CMake 4 нет `AndroidNdkModules` (нужен для cpufeatures). ABI `arm64-v8a` и `x86_64`, как у TDLib: `minSdk` 31, 32-битные `armeabi-v7a` / `x86` не нужны. Страницы 16 КБ, `-Wl,--gc-sections`, без LTO (у Telegram оно фактически отключено: `voip/CMakeLists.txt` затирает `CMAKE_CXX_FLAGS`).
- **Доставка** — как TDLib и rlottie: workflow на каждую ABI (matrix), архив `voip.zip` (`voip/libs/<abi>/libtgcallsjni.so`) публикуется как GitHub Release `voip-tg<коммит>-jni<N>` и не становится Latest. Модуль `core:voip` скачивает его Gradle-таском по `voip.release` из `gradle.properties`. Бинари в git не коммитим.
- **Kotlin:** `core:voip` → `TgCalls` грузит `.so` лениво, при первом обращении: на старт приложения библиотека не влияет. Прототип отдаёт `versions()` и `maxLayer()` для `callProtocol`, их показывает «Компоненты (debug)» → «Звонки (tgcalls)».

### Связка с TDLib

- `callProtocol`: `udpP2p = true`, `udpReflector = true`, `minLayer = 65` (как `CALL_MIN_LAYER` у Telegram Android), `maxLayer = TgCalls.maxLayer()` (92), `libraryVersions = TgCalls.versions()`. Это `2.7.7`, `5.0.0` (`InstanceImpl`), `7.0.0`–`9.0.0`, `12.0.0`, `13.0.0` (`InstanceV2Impl`) и `10.0.0`, `11.0.0` (`InstanceV2ReferenceImpl`), а на arm64 ещё `18.0.0`, `19.0.0` (`InstanceV2PumpImpl` на wasm, только под `__aarch64__`). `Meta::Versions()` отдаёт их отсортированными как строки, Telegram Android шлёт в том же порядке. Тот же протокол уходит и в `createCall`, и в `acceptCall`.
- Диффи — Хеллмана, проверку `g_a_hash` и эмодзи ключа делает TDLib, сами мы ничего не считаем (у Telegram Android это делает `VoIPService`).
- `callStateReady` → `tgcalls::Descriptor`:
  - `version` — первая версия из `protocol.libraryVersions` собеседника, которую знает наш tgcalls (как у Telegram Android);
  - `encryptionKey` (256 байт) и `call.isOutgoing` → `EncryptionKey { value, isOutgoing }`;
  - `servers`: `callServerTypeTelegramReflector(peerTag, isTcp)` → `Endpoint` (id, IPv4/IPv6, порт, `peerTag`, UDP/TCP-реле), `callServerTypeWebrtc(username, password, supportsTurn, supportsStun)` → `RtcServer`;
  - `allowP2p` → `Config.enableP2P`, `config` (JSON с сервера) → глобальный конфиг tgcalls, `customParameters` → `Config.customParameters`.
- Сигнализация: колбэк `Descriptor.signalingDataEmitted` → `sendCallSignalingData(callId, data)`, а `updateNewCallSignalingData` → `Instance::receiveSignalingData`. Состояния — `Descriptor.stateUpdated` / `signalBarsUpdated`, уровень звука, удалённое видео — тоже колбэками в Kotlin.
- Конец звонка: `discardCall(callId, isDisconnected, "", duration, isVideo, connectionId = getPreferredRelayId())`, затем `sendCallDebugInformation` (`getDebugInfo`) и по желанию `sendCallRating`. `PersistentState` tgcalls храним в файле между звонками.

### Android-часть (для #409 / #410 / #411)

- **Telecom:** Jetpack `androidx.core:core-telecom` (`CallsManager`, self-managed ConnectionService под капотом). Он регистрирует звонок в системе: другие звонки, гарнитура, часы, авто. Он же ставит `MODE_IN_COMMUNICATION` и аудиофокус и отдаёт выходы звука (наушник / динамик / Bluetooth / провод) через `availableEndpoints` и `requestEndpointChange` — свой `AudioManager`-код не пишем. Telegram Android делает то же на голом `ConnectionService` (`TelegramConnectionService`).
- **Служба:** foreground service на время звонка, `foregroundServiceType="phoneCall|microphone"`, для видео ещё `camera`. Нужны разрешения `FOREGROUND_SERVICE_PHONE_CALL`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_CAMERA`, `MANAGE_OWN_CALLS`, `BLUETOOTH_CONNECT` (runtime, Android 12+), `MODIFY_AUDIO_SETTINGS`, `WAKE_LOCK`, а `RECORD_AUDIO` и `CAMERA` уже есть.
- **Уведомления:** `Notification.CallStyle` (есть с API 31 — нашего `minSdk`): `forIncomingCall` с full-screen intent на экран вызова и `forOngoingCall` во время звонка. С Android 14 `USE_FULL_SCREEN_INTENT` вне Play может быть выключен: проверять `canUseFullScreenIntent()` и вести в настройки.
- **Входящие по пушу:** FCM → `processPushNotification` → `updateCall` (pending, входящий) → уведомление CallStyle. Push есть только в release (ADR-0005, ADR-0014). В `dctest` / `releaseTestdc` входящий звонок приходит, только пока TDLib на связи: для проверки на test DC держать приложение открытым.
- **Java-часть WebRTC:** нативный WebRTC вызывает классы `org.webrtc.*` по именам (JNI). Их берём из `TMessagesProj/src/main/java/org/webrtc` того же коммита (BSD) и отвязываем от классов Telegram (`FileLog`, `ApplicationLoader`, `LivePlayer` …). Плюс keep-правило R8 на `org.webrtc.**`. Платформенный код tgcalls ищет захват камеры по имени `org/telegram/messenger/voip/VideoCapturerDevice` — для видео (#411) пишем свой класс с этим именем (или патчим строку в CMake).

### Лицензии

- tgcalls — LGPL-3.0, WebRTC, opus, libvpx, libyuv, dav1d — BSD, BoringSSL — ISC/OpenSSL, openh264 — BSD-2, FFmpeg — LGPL-2.1, wasm-micro-runtime — Apache-2.0, Abseil — Apache-2.0, tde2e (TDLib) — BSL-1.0.
- LGPL выполняется так: tgcalls живёт в отдельной `libtgcallsjni.so`, которую приложение грузит динамически. Исходники (наш мост, CMake, точные коммиты) лежат в публичном репозитории, а ссылки на них — в заметках к релизу библиотеки. GPL-2.0-код Telegram Android (tgnet, их JNI и Java VoIP-классы) в бинарь не попадает. Их `voip/CMakeLists.txt` используется только при сборке.
- H.264 (openh264) — патентный вопрос: лицензия Cisco покрывает только их собственные бинари, а `.a` собран Telegram. Для личного клиента вне Play это принимаем.
- Юридической проверки не было. Для публичного релиза — экран «Лицензии» со списком выше (отдельная подзадача).

## Последствия

- Обновление tgcalls / WebRTC: поменять `TELEGRAM_COMMIT`, дождаться релиза `voip-…`, поднять `voip.release`. Поменяли `native/voip` — поднять `JNI_VERSION`. Если Telegram поменяет состав `voip/CMakeLists.txt` или prebuilt-библиотек, придётся поправить наш `CMakeLists.txt`.
- Сборка одной ABI — 6–8 мин на `ubuntu-latest` (~1750 целей Ninja), ABI собираются параллельно, весь workflow — ~8 мин. Кэш не нужен: релиз собирается один раз на коммит. Повторный запуск с тем же тегом пропускается (`check`).
- APK: `libtgcallsjni.so` после strip весит 20,9 МБ (arm64-v8a) и 22,1 МБ (x86_64). `.so` лежат в APK без сжатия (`useLegacyPackaging = false`, как и `libtdjni.so`), поэтому `releaseTestdc` вырос с 63,7 до 106,8 МБ, а в zip-артефакте CI — на ~18 МБ. На телефоне занято +21 МБ: ставится одна ABI и не распаковывается. Чтобы урезать скачивание, нужны ABI-splits для release-APK — отдельная задача.
- Зависим от того, что Telegram публикует `TMessagesProj/jni` с prebuilt-`.a`. Если они пропадут, придётся собирать их скриптами `prebuild/build_*.sh` того же коммита.
