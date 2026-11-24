# 0014. Release-сборка

- Статус: принято
- Дата: 2026-11-24

## Контекст

До v0.8.0 включительно релиз означал git-тег, а APK брали из артефактов CI. Это была debug-сборка: без R8, подписанная общим debug-ключом (ADR-0001). Debug-Compose заметно медленнее release (профилирование открытия чата в #259, jank холодного старта в #343). Продовские `FCM_*` лежат в GitHub Secrets (ADR-0005). Firebase-приложение зарегистрировано на `io.github.iufb.reach`.

## Решение

- **Распространение:** подписанный APK в GitHub Release по тегу `v*`. Google Play вне рамок.
- **applicationId:**
  - `release` — `io.github.iufb.reach`, «Reach (Unofficial)»;
  - `debug` — `io.github.iufb.reach.debug`, «Reach Dev», с графитовой подложкой иконки;
  - `dctest` — `io.github.iufb.reach.testdc`, «Reach Test», с оранжевой подложкой (не меняется);
  - `releaseTestdc` — как `dctest`, но с R8.

  У каждой сборки своя база TDLib (свой `filesDir`), и все ставятся на один телефон рядом.
- **Push только в release.** FCM-проект привязан к `io.github.iufb.reach`, поэтому в `debug`, `dctest` и `releaseTestdc` поля `FCM_*` пустые, и `PushRegistrar` push не включает. Push проверяет владелец на release-APK и своём аккаунте.
- **Ключ подписи** владелец создаёт сам по визарду `scripts/release-keystore-wizard.sh`. Хранит у себя и делает резервную копию. В GitHub Secrets лежат `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Ни файл, ни пароль не проходят через агента и не коммитятся.
- **CI:**
  - на PR и main — `assembleReleaseTestdc` (R8, test DC, подпись debug-ключом). Поломки R8 ловятся до релиза, и агент проверяет minified-сборку на устройстве в test DC;
  - по тегу `v*` — подписанный `assembleRelease` с продовскими `FCM_*`, проверка совпадения тега с `versionName` и GitHub Release с APK и `mapping.txt`.
- **Baseline profile** генерирует отдельный ручной workflow (эмулятор + macrobenchmark), профиль коммитится в репозиторий.

## Последствия

- Debug-сборка после перехода на `.debug` — это новое приложение с пустой базой, нужен перелогин.
- Потеря release-ключа — это невозможность выпускать обновления: пользователям придётся удалить приложение и поставить заново. Резервная копия обязательна.
- Keep-правила R8 для JNI (TDLib `org.drinkless.tdlib`, rlottie) обязательны: нативный код создаёт объекты и вызывает методы по именам, и поломка проявляется только в рантайме.
