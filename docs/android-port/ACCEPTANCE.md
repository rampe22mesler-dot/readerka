# Сборка, установка и приёмка Android-порта

Изменения развивают существующий `app/`. Десктоп — референт; `git diff --exit-code -- windows` должен оставаться успешным. Этот документ не подтверждает запуск на физическом Huawei.

## Подготовка в облаке

```bash
cd /workspace/readerka
bash scripts/cloud-setup.sh
source scripts/cloud-env.sh
cd windows
./gradlew --no-daemon :desktop:interopFixture --console=plain
cd ..
python3 scripts/prepare-android-interop.py
./gradlew --no-daemon :shared:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Нужны JDK 17, SDK platform/build-tools 35, NDK 26.3.11579264, `make`, `tar`, `unzip`, Python 3 и curl. Скрипт проверяет загрузки JDK/SDK и SHA-256 приложенных исходников нативных библиотек. Gradle использует закреплённый wrapper/checksum. Все caches находятся в `/workspace/toolchains` и `app/build`; личные ключи для сборки не требуются. Прокси и доверенный CA платформы сохраняют TLS-проверку. Изменение Android applicationId не служит заменой миграции.

Сборки:

```bash
./gradlew --no-daemon :app:assembleDebug
# Сразу скопируйте app/build/outputs/apk/debug/app-debug.apk: следующая команда заменит этот файл.
./gradlew --no-daemon :app:assembleDebug -PreaderkaPreview=true
```

Обычный пакет — `org.readerka`, Preview — `org.readerka.preview`; versionCode 10, minSdk 28, targetSdk 35. Поддерживаемые ABI APK: arm64-v8a и x86_64. Android 32-bit не заявлен. Нативный DjVu/TIFF на ARM64 собран; физический запуск ARM64 проверяется отдельно.

APK подписан проверочным debug-ключом среды. Возможность установки поверх прежнего Readerka зависит от совпадения подписи. При `INSTALL_FAILED_UPDATE_INCOMPATIBLE` **не удаляйте приложение с библиотекой**. Сначала экспортируйте ZIP через прежнее приложение; используйте свободный вариант пакета или тестовое устройство. Релизный ключ не заменён и не включён в репозиторий/ZIP/артефакты. Для совместимого APK применяется обычное обновление `adb install -r` с явными миграциями Room.

## Эмулятор и тесты

```bash
bash scripts/cloud-android-emulator.sh > /workspace/toolchains/emulator.log 2>&1
# В другой сессии:
source scripts/cloud-env.sh
adb devices
export ANDROID_SERIAL=emulator-5554   # подставьте фактический serial
adb shell getprop sys.boot_completed # должно быть 1
./gradlew --no-daemon :app:connectedDebugAndroidTest --console=plain
```

Скрипт создаёт AOSP API 30 без GMS, 1280×900, 160 dpi. `READERKA_ANDROID_API=28 bash scripts/cloud-android-emulator.sh` выбирает минимальный API. В этой машине нет `/dev/kvm`; boot/GUI работают в программной эмуляции. Не запускайте два эмулятора без явного ANDROID_SERIAL. Состояние файлов можно сохранить в snapshot среды; процессы необходимо запускать заново.

Перед прогоном получите `adb exec-out screencap -p > /tmp/emulator-ready.png` и проверьте экран. Даже при boot_completed=1 медленный boot может оставить диалог ANR службы System UI, перекрывающий ввод. Зафиксируйте его и устраните до повторения затронутых проверок; не засчитывайте заблокированные тесты как успешные. Для диагностики используйте logcat и время возникновения ANR. Не перезапускайте ADB и не вызывайте `adb root` во время instrumentation.

На API 28 полный прогон прерывался watchdog из-за блокирования системных WindowManager/SurfaceFlinger; это не засчитывается как успешная UI-приёмка. Первичный полный отчёт также содержит ошибки прежних сценариев интерфейса. См. STATUS.md для итоговых результатов каждого прогона. Не исключайте проверки и не считайте пропуски проходом.

Для получения файлов до автоматического удаления APK тестовым Gradle runner:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Необязательно для медленного программного эмулятора: штатная AOT-компиляция.
adb shell cmd package compile -m speed -f org.readerka
adb shell cmd package compile -m speed -f org.readerka.test
adb shell am instrument -w -r -e listener org.readerka.PortXmlListener org.readerka.test/androidx.test.runner.AndroidJUnitRunner
# Проверяйте INSTRUMENTATION_STATUS_CODE, число тестов и OK, а не только shell exit code.
adb exec-out run-as org.readerka cat files/test-results/port-tests.xml > /tmp/port-tests.xml
adb exec-out run-as org.readerka cat files/android-port-roundtrip.zip > /tmp/android-port-roundtrip.zip
adb exec-out run-as org.readerka cat files/android-port-expected.json > /tmp/android-port-expected.json
java -cp 'windows/desktop/build/windows/lib/*' scripts/VerifyAndroidReturn.java \
  /tmp/android-port-roundtrip.zip /tmp/android-port-expected.json \
  app/build/generated/interop-assets/interop/to-android.zip
adb exec-out run-as org.readerka tar -cf - files/ui-previews > /tmp/readerka-ui-previews.tar
```

Windows-проверка использует неизменённые desktop/shared библиотеки. Она сравнивает все категории, поля с повторными размещениями, ID блоков и источник конспекта, PDF SHA-256, текст/геометрию Android-цитаты и попадание прямоугольников в десктопный рендер. `desktop-return-quote.png` — диагностический рендер PDF с якорем, а не снимок desktop-приложения. PortXmlListener сохраняет частичный отчёт после каждого теста и complete=true только после testRunFinished; он не подавляет проверки. Не перезапускайте ADB во время прогона.

Android PNG получены PixelCopy из запущенного интерфейса на синтетических данных.

Снимки: [поле](screenshots/android-tablet-field.png), [конспект](screenshots/android-tablet-consp.png), [редактор](screenshots/android-tablet-consp-editor.png), [граф](screenshots/android-tablet-graph.png). Они подтверждают конкретные экраны; результаты проверок приведены отдельно.

## Ручной обмен

Экспортируйте полную библиотеку в ZIP через системный выбор файла. На втором устройстве импорт сначала показывает проверенный снимок и различия. «Сохранить местные» оставляет местные объекты при совпадении ID; «Обновить из архива» принимает входящие объекты, сохраняя местные дополнительные; «Заменить библиотеку» удаляет дополнительные местные объекты и требует дополнительного подтверждения. Поле разрешается целиком, без слияния отдельных карточек/абзацев. Перед применением создаётся резервный ZIP; Room-квитанция и файловый журнал обеспечивают восстановление после прерывания.

Внутренние резервные ZIP удаляются вместе с приложением: нужную копию экспортируйте в выбранную пользователем папку. Токены, PKCE-коды, локальные черновики, вкладки, история навигации и настройки графа не входят в ZIP библиотеки. Черновики и выделения сохраняются локально по мере изменений.

Dropbox находится в настройках. Нужен App key приложения Dropbox с App folder и разрешениями `account_info.read`, `files.metadata.read`, `files.content.read`, `files.content.write`; App secret не нужен. Вход — PKCE через браузер и одноразовый код. Отправка полного ZIP и замена облачной копии подтверждаются отдельно; загрузка использует ревизию. Скачивание ведёт в тот же экран сравнения. Токен хранится с AES-GCM/Android Keystore. Работа с реальным аккаунтом пока не подтверждена; локальное чтение не зависит от него.

## Huawei HarmonyOS 4.2 — пока не выполнено

- [ ] Уточнить Android API, ABI и разрешение; установить проверочный APK без удаления личной библиотеки.
- [ ] Альбомный планшет: PDF + конспект, вкладки, перенос вкладки, изменение ширины, клавиатура/мышь.
- [ ] Только касания: импорт, выделение/цитата, комментарий, папки, поле, экспорт; подключение/отключение периферии сохраняет текст.
- [ ] 320/360 dp, альбомное окно, увеличенный шрифт и экранная клавиатура: команды и сохранение доступны, скрытая область сохраняет позицию/текст.
- [ ] Поворот, split-screen и уничтожение процесса: черновики, выбранная область, выделение, координаты поля и вид графа восстанавливаются без повторного импорта.
- [ ] PDF с поворотами/CropBox, OCR/JBIG2, кириллицей/CJK/лигатурами и большие сканы: визуальное попадание цитаты и приемлемое время/память.
- [ ] ZIP в обе стороны, отмена, три политики, повреждённый архив, мало места; обновление подписанного прежнего APK с сохранением базы.
- [ ] Без сети/GMS: чтение и редактирование; затем отдельная проверка PKCE и двух подтверждений Dropbox на разрешённой синтетической библиотеке.

Снимки текущей сборки после восстановления Activity: [PDF + конспект, светлая тема](screenshots/readerka-workspace-light.png), [тёмная тема](screenshots/readerka-workspace-dark.png). Они показывают фактически запущенное приложение на синтетических данных; сами по себе не подтверждают полный тестовый набор.

[Длинный конспект и реальная экранная клавиатура](screenshots/readerka-ime-caret.png): отдельный сценарий проверил видимость курсора и сохранение выше IME. Чек-лист физического Huawei от этого не становится выполненным.

[Изменение границы областей и сохранение над видимой IME](screenshots/readerka-ime-split.png): усиленный сценарий проверяет IME после ввода, native focus и видимость строки курсора перед снимком/сохранением.
