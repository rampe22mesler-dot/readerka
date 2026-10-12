# Readerka 1.0.0 для Windows

Приложение для чтения PDF, EPUB, FB2 и DjVu, работы с цитатами, заметками и конспектами. Рабочее пространство содержит вкладки, папки, материалы книги, исследовательские поля и граф связей библиотеки. Интерфейс на русском языке, светлая и тёмная темы.

## Установка

Windows 10/11 x64. Скачать `readerka-1.0.0-windows-x64.msi` на [странице релиза](https://github.com/rampe22mesler-dot/readerka/releases/tag/v1.0.0), открыть файл и пройти установку. Java включена. Установщик создаёт ярлыки и запись для удаления приложения. Подпись издателя отсутствует.

Для запуска без установки распаковать `readerka-1.0.0-windows-x64-portable.zip` полностью и открыть `Readerka.exe`. `Readerka-Diagnostics.exe` выводит ошибки в консоль; `Readerka-Diagnostics.exe --version` выводит версию.

Библиотека хранится в `%LOCALAPPDATA%\Readerka` отдельно от программы. Одновременно её может открыть один экземпляр. Удаление приложения сохраняет библиотеку. Для переноса использовать «Файл → Синхронизация»: полный ZIP, сравнение изменений и резервная копия перед импортом. Текущий формат ZIP — 5; старые Android-версии его не принимают.

Чтение, редактирование и файловый перенос работают без сети. Google Переводчик отправляет выбранный текст Google. Dropbox требует своего App key и входа пользователя; загрузка выполняется вручную. Личные данные в дистрибутив не входят.

F1 открывает справку. Подробности: [перенос библиотеки](docs/TRANSFER.md), [исследовательские поля](docs/RESEARCH-FIELD.md), [проверка выпуска](docs/RELEASE-1.0.0.md).

## Разработка

Самостоятельный Gradle-проект содержит только `desktop` и `shared`; Android SDK не требуется. В репозитории он находится в папке `windows`. Требуются JDK 17 и доступ к Maven Central, Google Maven и Gradle Plugin Portal при первой сборке.

```powershell
cd windows
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat --no-daemon :shared:test :desktop:test :desktop:windowsLibraries -Djava.awt.headless=true
& "$env:JAVA_HOME\bin\java.exe" -Dfile.encoding=UTF-8 -cp 'desktop\build\windows\lib\*' org.readerka.desktop.MainKt --data-dir '.dev-data'
```

После подготовки кеша можно добавить `--offline`. Параметр `--data-dir` изолирует тестовые данные от личной библиотеки. Исходники: `desktop/src/main/kotlin`, `shared/src/main/kotlin`. Тесты находятся в соответствующих каталогах `src/test`.

В исходной локальной папке используются `Local.ps1 -Action Build`, `Local.ps1 -Action Test` и `Local.ps1 -Action Run`. Последняя команда выбирает `.dev-data`.

Интерфейсные тесты стандартный headless-прогон пропускает. Для них нужен рабочий стол Windows и `-Djava.awt.headless=false`; Robot-сценарии используют мышь и клавиатуру. Сетевые сценарии Dropbox проверяются локальным HTTP-сервером; работа с реальным аккаунтом требует отдельной проверки.

## Сборка установщика

Версия задаётся в `gradle.properties`. Нужны JDK 17 с `jpackage` и распакованные [WiX 3.14.1 binaries](https://github.com/wixtoolset/wix3/releases/tag/wix3141rtm).

```powershell
.\scripts\Build-Release.ps1 -JdkHome 'C:\path\to\jdk-17' -WixHome 'C:\path\to\wix314'
# С подготовленным кешем:
.\scripts\Build-Release.ps1 -JdkHome 'C:\path\to\jdk-17' -WixHome 'C:\path\to\wix314' -Offline
```

Сценарий проверяет headless-тесты, собирает JAR, создаёт встроенную Java, нативные EXE, MSI и portable ZIP. Результаты и SHA-256: `desktop/build/release`. UUID обновления MSI закреплён в сценарии и должен сохраняться при последующих выпусках. Установщик не включает библиотеку, учётные данные, кеш Gradle или резервные копии.

Лицензии и источники сторонних компонентов: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Номер 1.0.0 относится к Windows-приложению; Android-код и прежние APK остаются в основной части репозитория.
