# Reach

Неофициальный клиент Telegram для Android на Jetpack Compose + [TDLib](https://github.com/tdlib/td). Подробности в [PRODUCT.md](PRODUCT.md), решения — в [docs/adr](docs/adr).

## Сборка

Локально ничего не собирается ([ADR-0001](docs/adr/0001-ci-only-builds.md)): TDLib и APK собирает только GitHub Actions.

- **TDLib** — workflow `TDLib` публикует `tdlib.zip` в GitHub Release. Версия для приложения задаётся в `gradle.properties` (`tdlib.release`).
- **APK** — workflow `Android` собирает debug APK на каждый push и PR. Артефакт `reach-debug` хранится 14 дней.

Секреты репозитория: `APP_ID`, `APP_HASH` (с [my.telegram.org](https://my.telegram.org)) и `DEBUG_KEYSTORE_BASE64`.

## Установка на устройство

```sh
scripts/install-apk.sh          # последний успешный APK текущей ветки
scripts/install-apk.sh main     # или указанной ветки
```

Нужны `gh` (авторизованный) и `adb` с подключённым устройством.
