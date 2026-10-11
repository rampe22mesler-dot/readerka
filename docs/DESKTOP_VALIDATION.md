# Проверки Readerka 0.9.1 Desktop Preview

Проверено 11 октября 2026 года. Основа — Android 0.9, продолжение 0.7; изменения 0.8 не включались. Настольный интерфейс использует Swing/FlatLaf, общие модели и формат ZIP вынесены в модуль `shared`.

## Выполненные проверки

| Проверка | Результат |
|---|---|
| Общая JVM-логика: архивы, модели, папки, поиск, ссылки, форматирование, геометрия выделения, состояние областей | 45 тестов, 0 ошибок, 0 пропусков |
| Хранилище ПК: черновики, версии, политики импорта, папки/теги, различия ссылок и вставки текста, восстановление после прерванного импорта PDF | 10 тестов, 0 ошибок |
| Настоящие Swing/Robot сценарии: клавиатура, мышь, редактор, буфер обмена, сворачивание разделов, папки, полный экран, закрытие второй области, цитата из PDF двумя способами | 6 тестов, 0 ошибок, 0 пропусков |
| Регрессия Android: LibraryIntegrationTest, ReaderMigrationTest, PdfWorkflowTest, ConspWorkflowTest | 13 тестов, 0 ошибок |
| Обмен ПК → Android → архив для ПК и управление сравнением импорта касанием, аппаратной клавиатурой, мышью | 4 теста, 0 ошибок |
| Android lint | 0 ошибок, 31 предупреждение |
| Android APK и APK инструментальных тестов | Собраны успешно |

Текущие XML-отчёты и журналы Android сохранены в [test-results/desktop-0.9.1](test-results/desktop-0.9.1). Скриншоты получены из настоящих окон на синтетической библиотеке: [редактор](screenshots/readerka-0.9.1-desktop-editor.png), [PDF и конспект](screenshots/readerka-0.9.1-desktop-pdf-and-editor.png). Тестовые библиотеки и генератор архива не входят в основной JAR или APK.

## Совместимость переноса

Настольный генератор создал ZIP версии 4 с PDF, четырьмя поворотами страницы, цитатой, конспектом, форматированием, ссылками, папками разных категорий, тегами, закладками и версиями. Android импортировал его в настоящую Room-базу, проверил текст и геометрию PDF, изменил конспект и экспортировал архив обратно. Настольный проверяющий код затем прочитал возвращённый архив и сравнил идентификаторы, содержимое, абзацы, стили, ссылки, связи, закладки и PDF. Автоматическая резервная копия сохраняет прежнее состояние; проверка внешних ключей Room не обнаружила нарушений.

Preview сохраняет пакет `org.readerka.preview`, схему Room 4 и сертификат SHA-256 `5b8ed068c6ad5a5377f6e19f613f0d943b597c98a99cf895d93de651df450dd7`. APK устанавливался поверх существующего Preview без удаления приложения. Перенос является ручным обменом ZIP, а не синхронизацией. Для обновления существующих записей на планшете требуется Preview 0.9.1; подробности — в [WINDOWS.md](WINDOWS.md).

## Windows-пакет и границы проверки

ZIP содержит платформенно независимые JAR, Windows x64 SQLite DLL и официальный Temurin JRE 17.0.16+8 для Windows x64. SHA-256 исходного JRE проверен: `d35b05f4832215d8877d0dbf15c6370c854d7d5b812f890a9c0db8ad412a6bf2`. Упаковщик проверяет PE-архитектуру `java.exe`, `javaw.exe`, `jvm.dll`, наличие точки входа и отсутствие баз/ключей. Внутри ZIP есть `checksums.json`, рядом — SHA-256 всего архива. Полные контрольные суммы выпуска находятся в [SHA256SUMS.txt](../downloads/SHA256SUMS.txt).

Настольные сценарии реально запускались на Linux x64 с JDK 17 и Xorg dummy (`DISPLAY=:98`); Android — на программном эмуляторе API 28. **Запуск CMD, Java и окон на Windows 10/11 ещё не проверен.** Workflow [desktop-windows.yml](../.github/workflows/desktop-windows.yml) настроен для сборки и тестов на `windows-latest`; наличие workflow не означает успешного запуска Actions. Физический Huawei MatePad, Windows SmartScreen, внешняя страница перевода и взаимодействие с настоящим Obsidian здесь не проверялись. Сканы без текстового слоя читаются, но OCR не добавлен.

Минимальная проверка на вашем ПК: распаковать ZIP целиком, открыть `Readerka.cmd`, импортировать копию архива планшета, отредактировать конспект, выделить цитату в PDF, закрыть вторую область в полном экране, перезапустить программу и экспортировать ZIP. При ошибке запуска открыть `Diagnostics.cmd` или `%LOCALAPPDATA%\Readerka\startup.log`.

## Повторить в подготовленной облачной среде

```bash
source scripts/cloud-env.sh
bash scripts/cloud-desktop-display.sh
DISPLAY=:98 xdpyinfo
DISPLAY=:98 ./gradlew :shared:test :desktop:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -PreaderkaPreview=true --max-workers=2
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r -e class org.readerka.DesktopTransferTest,org.readerka.ImportReviewUiTest org.readerka.preview.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 exec-out run-as org.readerka.preview cat cache/readerka-android-roundtrip.zip > /tmp/readerka-android-roundtrip.zip
./gradlew :desktop:verifyInterop -PinteropArchive=/tmp/readerka-android-roundtrip.zip --max-workers=2
bash scripts/build-windows.sh
```

Эмулятор должен быть полностью загружен. Ручной запуск инструментальных тестов через ADB сохраняет установленное приложение; Gradle `connectedDebugAndroidTest` может удалять его после тестов. Без графического экрана тесты Swing помечаются пропущенными — такой результат не подтверждает работу интерфейса.
