# 0005. Push через собственный FCM-проект

- Статус: принято
- Дата: 2026-11-20

## Контекст

Без push клиент узнаёт о новых сообщениях только пока открыт. Был риск (PRODUCT.md §10), что сторонний клиент не сможет получать push от Telegram и придётся держать постоянное соединение в foreground service.

На my.telegram.org в настройках приложения есть раздел **Push credentials → FCM service account**. Туда загружается ключ сервисного аккаунта нашего Firebase-проекта, и серверы Telegram сами шлют push на наши устройства через FCM.

## Решение

- Push идёт через собственный Firebase-проект. Цепочка: FCM-токен → `registerDevice(DeviceTokenFirebaseCloudMessaging)` → payload из `onMessageReceived` → `processPushNotification`. Что показывать, решает TDLib (`UpdateNotificationGroup` и т. п.), в том числе с учётом mute.
- **Ключ сервисного аккаунта** (JSON из Firebase Console → Service accounts) загружается вручную на my.telegram.org. Ни в репозитории, ни в GitHub Secrets его нет: приложению он не нужен.
- **Клиентский конфиг Firebase** не коммитится, `google-services.json` и плагин `google-services` не используются. Значения из него (`FCM_PROJECT_ID`, `FCM_APP_ID`, `FCM_API_KEY`, `FCM_SENDER_ID`) лежат в GitHub Secrets и пробрасываются в `BuildConfig`, как `api_id` в ADR-0003. `FirebaseApp` инициализируется вручную через `FirebaseOptions`.
- Без этих секретов (сборка в IDE, PR из форков) сборка проходит, push просто выключен, остальное приложение работает.
- Foreground service с постоянным соединением не делаем.

## Последствия

- Настройка разовая и ручная: Firebase-проект, загрузка ключа на my.telegram.org, четыре секрета в GitHub.
- Если ключ на my.telegram.org отозван или протух, push молча перестаёт приходить. Проверяется на устройстве перед релизом.
- Работоспособность схемы окончательно подтверждается в задаче подключения FCM (#100).
