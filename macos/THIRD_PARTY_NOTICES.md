# Сторонние компоненты Readerka для macOS

JVM-компоненты и их версии перечислены в [общей ведомости](../windows/THIRD_PARTY_NOTICES.md). Тексты лицензий находятся внутри JAR и в `Contents/app/third-party/licenses` приложения. JNA 5.17.0 используется по Apache-2.0 для доступа к системной Связке ключей; исходники: https://github.com/java-native-access/jna/tree/5.17.0.

Eclipse Temurin / OpenJDK 17 используется по GPL-2.0 with Classpath Exception. Точная версия определяется установленным JDK сборки; лицензии модулей включены в `Contents/runtime/Contents/Home/legal`. Исходники: https://github.com/adoptium/jdk17u.

В macOS-сборке отдельно исполняются нативные `ddjvu` и `djvused` из DjVuLibre 3.5.29 (GPL-2.0-or-later). Они статически связаны с IJG JPEG 6b, libtiff 4.0.10 и zlib 1.2.11. Исходные архивы с SHA-256, лицензии и сценарий сборки прилагаются к репозиторию; архивы и тексты лицензий также включены в `Contents/app/third-party`. Для JPEG обновляются `config.guess`/`config.sub` из того же архива DjVuLibre. В zlib исправлена проверка classic Mac OS в `zutil.h`: современная macOS использует POSIX `fdopen`. Точное изменение воспроизводит сценарий `macos/scripts/build-djvu.sh`; его копия включена в дистрибутив.

Системные Security.framework и CoreFoundation принадлежат Apple и используются из установленной macOS. Windows EXE/DLL, Microsoft Visual C++ Runtime и WiX в Mac-приложение не входят. Сведения о них в общей ведомости относятся к Windows-сборке.
