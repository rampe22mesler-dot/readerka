# Readerka 1.0.0 для macOS

Настольная Readerka с теми же книгами, цитатами, заметками, конспектами, исследовательскими полями и графом библиотеки, что и Windows 1.0.0. Общие Kotlin/Swing-исходники находятся в `windows/desktop` и `windows/shared`; Android SDK для сборки не нужен.

## Установка

[Скачать DMG для MacBook Neo / Apple Silicon](https://github.com/rampe22mesler-dot/readerka/releases/download/v1.0.0-macos/readerka-1.0.0-macos-arm64.dmg) · [DMG для Intel](https://github.com/rampe22mesler-dot/readerka/releases/download/v1.0.0-macos/readerka-1.0.0-macos-x64.dmg) · [Все файлы релиза, ZIP и SHA-256](https://github.com/rampe22mesler-dot/readerka/releases/tag/v1.0.0-macos)

macOS 12 или новее. Для Mac на Apple Silicon, включая **MacBook Neo** и модели с чипами M1 и новее, выбирайте `readerka-1.0.0-macos-arm64.dmg`, для Intel — `readerka-1.0.0-macos-x64.dmg`. Откройте DMG и перенесите `Readerka.app` в «Программы». Альтернативный ZIP содержит ту же `.app`; распакуйте его полностью. Java и декодер DjVu уже включены, Homebrew не нужен.

Сборки имеют техническую ad-hoc подпись, без сертификата Apple Developer ID и нотарификации. Если macOS блокирует первый запуск, попробуйте открыть приложение, затем выберите «Системные настройки → Конфиденциальность и безопасность → Всё равно открыть» и подтвердите запуск. Отключать Gatekeeper целиком не нужно.

Библиотека хранится отдельно от программы в `~/Library/Application Support/Readerka`. Удаление `.app` не удаляет книги. Одновременно библиотеку открывает один экземпляр. Для переноса с Windows используйте «Файл → Синхронизация → Выгрузить данные» и загрузите ZIP на Mac. Обе настольные версии используют формат 5; старые Android-версии его не принимают. Локальные настройки и вход Dropbox не переносятся.

Основные клавиши: ⌘O — добавить книгу, ⌘S — сохранить, ⌘F — поиск, ⌘W — закрыть вкладку, ⌘Z / ⌘⇧Z — отмена / повтор. Меню находится в системной строке macOS; ⌘Q завершает работу с сохранением черновиков. Во время импорта/экспорта нужно дождаться завершения операции. Справка F1 учитывает клавишу ⌘. Вход Dropbox хранится в Связке ключей macOS отдельно для каждой библиотеки; macOS может запросить разрешение на доступ. Синхронизация выполняется только вручную.

## Сборка на Mac

Нужны JDK 17 с `jpackage` (например, Eclipse Temurin) для архитектуры вашего Mac и Xcode Command Line Tools (`xcode-select --install`). При первой сборке нужен доступ к репозиториям Gradle/Maven. Нативные зависимости собираются из архивов в репозитории с проверкой SHA-256.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
bash macos/build.sh
# После заполнения кеша Gradle:
bash macos/build.sh --offline
```

Результаты: `windows/desktop/build/release/macos-arm64/` или `macos-x64/`: DMG, ZIP и `SHA256SUMS.txt`. Архитектуры собираются отдельно на соответствующих Mac. Скрипт запускает headless-тесты, проверку Связки ключей в отдельном временном хранилище, затем проверяет настоящий launcher, SQLite, импорт кириллицы, отрисовку PDF и DjVu через встроенную Java и файлы упакованной `.app`.

GitHub Actions: workflow **macOS desktop** в [Actions](https://github.com/rampe22mesler-dot/readerka/actions/workflows/macos.yml) собирает обе архитектуры и сохраняет установщики и отчёты как artifacts. Автоматической публикации релиза нет.

Для разработки:

```bash
bash macos/scripts/build-djvu.sh
export READERKA_DJVU_DIR="$PWD/windows/desktop/build/native/djvu"
cd windows
bash ./gradlew :shared:test :desktop:test :desktop:run --args='--data-dir .dev-data'
```

`--data-dir` изолирует тестовую библиотеку. Robot-тесты интерфейса требуют графической сессии и `-Djava.awt.headless=false`; разрешения macOS на управление интерфейсом могут потребоваться отдельно. На Linux доступны общие JVM-тесты и сборка декодера для тестирования, но `.app`/DMG создаются только на Mac. Поведение Finder, системного меню, Gatekeeper и Dropbox с реальным аккаунтом следует проверить на физическом Mac.

Лицензии: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Подробное руководство: [справка настольной версии](../windows/README.md), [перенос библиотеки](../windows/docs/TRANSFER.md).
