# 0002. Доставка TDLib через GitHub Release и Gradle-таск

- Статус: принято
- Дата: 2026-11-15

## Решение

- Отдельный workflow (`workflow_dispatch`) собирает TDLib по зафиксированному коммиту под ABI `arm64-v8a` и `x86_64` и публикует `tdlib.zip` (`.so` + `TdApi.java` / `Client.java`) как GitHub Release.
- Версия TDLib задаётся в одном месте (`gradle.properties`).
- Gradle-таск в модуле `core:td` скачивает `tdlib.zip` нужной версии и распаковывает его. Распакованные файлы лежат в `.gitignore`.
- Бинари TDLib в git не коммитим (Git LFS тоже не используем).

## Последствия

- Обновление TDLib: пересобрать релиз и поменять версию в `gradle.properties`.
- Сборке приложения нужен доступ к GitHub Releases.
