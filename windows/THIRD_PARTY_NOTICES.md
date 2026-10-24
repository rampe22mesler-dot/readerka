# Сторонние компоненты Windows-версии 1.0.0

| Компонент | Версия | Лицензия и исходники |
|---|---|---|
| Eclipse Temurin / OpenJDK | 17.0.20.1+1 | GPL-2.0 with Classpath Exception; https://github.com/adoptium/jdk17u; лицензии каждого модуля включены в runtime/legal |
| Kotlin stdlib | 2.1.20 | Apache-2.0; https://github.com/JetBrains/kotlin/tree/v2.1.20 |
| JetBrains annotations | 13.0 | Apache-2.0; https://github.com/JetBrains/java-annotations |
| kotlinx.serialization | 1.8.0 | Apache-2.0; https://github.com/Kotlin/kotlinx.serialization/tree/v1.8.0 |
| AndroidX Room common | 2.7.1 | Apache-2.0; https://android.googlesource.com/platform/frameworks/support |
| AndroidX annotation | 1.9.1 | Apache-2.0; https://android.googlesource.com/platform/frameworks/support |
| FlatLaf | 3.6 | Apache-2.0; https://github.com/JFormDesigner/FlatLaf/tree/3.6 |
| Apache PDFBox / FontBox | 2.0.33 | Apache-2.0; https://pdfbox.apache.org/ |
| Apache Commons Logging | 1.2 | Apache-2.0; https://commons.apache.org/proper/commons-logging/ |
| Apache JBIG2 ImageIO | 3.0.4 | Apache-2.0; https://pdfbox.apache.org/ |
| CommonMark Java | 0.24.0 | BSD-2-Clause; https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.24.0 |
| Xerial SQLite JDBC | 3.49.1.0 | Apache-2.0 / BSD; https://github.com/xerial/sqlite-jdbc/tree/3.49.1.0 |
| SQLite | 3.49.1 | Public domain; https://sqlite.org/ |
| SLF4J API / NOP | 2.0.17 | MIT; https://github.com/qos-ch/slf4j/tree/v_2.0.17 |
| JNA | 5.17.0 | Apache-2.0; https://github.com/java-native-access/jna/tree/5.17.0; доступ к Связке ключей в macOS |
| DjVuLibre decoder | 3.5.29 | GPL-2.0-or-later; https://sourceforge.net/projects/djvu/files/DjVuLibre/3.5.29/ |
| IJG JPEG library | 6b | IJG license; https://www.ijg.org/files/jpegsrc.v6b.tar.gz |
| libtiff | 4.0.10 | libtiff license; https://download.osgeo.org/libtiff/tiff-4.0.10.tar.gz |
| zlib | 1.2.11 | zlib license; https://zlib.net/fossils/zlib-1.2.11.tar.gz |

Тексты лицензий и NOTICE из JAR собраны в `third-party/licenses`. Те же лицензии остаются внутри JAR. Текст GPL DjVuLibre также находится в `desktop/src/main/resources/native/djvu/COPYING.txt` и дистрибутиве как `DJVULIBRE-COPYING.txt`.

DjVuLibre выполняется отдельным процессом для преобразования книги и извлечения текста. Включены ddjvu, djvused и четыре DLL из официальной Windows-сборки DjVuLibre 3.5.29 / DjView 4.12. Источник пакета и его SHA-256 указаны в `desktop/src/main/resources/native/djvu/README.txt`. DjView и Qt в Readerka не входят. Исходники DjVuLibre и его нативных зависимостей прилагаются в `third-party`.

WiX 3.14.1 и JDK используются при упаковке. WiX и компилятор Java в установленное приложение не включаются. Авторские контурные значки Readerka не используют внешние шрифты значков. Лицензии сторонних компонентов не распространяются на книги и записи пользователя.


Microsoft Visual C++ Runtime 14.23.27820.0 (x86): msvcp140.dll и vcruntime140.dll, Microsoft Software License Terms. DLL извлечены из подписанного Microsoft vcredist_x86.exe, входящего в исходный пакет DjVuLibre; используются только отдельным процессом декодера. Системный runtime не устанавливается и не заменяется. Текст условий: third-party/licenses/Microsoft-Visual-Cpp-2019.
