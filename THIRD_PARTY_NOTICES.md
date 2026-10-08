# Открытые компоненты

Readerka использует опубликованные зависимости, а не копии чужого приложения.

| Компонент | Версия | Лицензия / источник |
|---|---|---|
| Kotlin / Compose compiler | 2.1.20 | Apache-2.0, https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | 1.10.2 | Apache-2.0, https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | 1.8.1 | Apache-2.0, https://github.com/Kotlin/kotlinx.serialization |
| AndroidX / Compose | BOM 2025.04.00 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support |
| Room | 2.7.1 | Apache-2.0, AndroidX |
| DataStore | 1.1.4 | Apache-2.0, AndroidX |
| PDFBox Android | 2.0.27.0 | Apache-2.0, https://github.com/TomRoush/PdfBox-Android |
| Apache PDFBox | Android port of 2.0.27 | Apache-2.0, https://pdfbox.apache.org |
| Bouncy Castle | 1.72, transitively via PDFBox | MIT-style, https://www.bouncycastle.org/licence.html |

Тексты Apache-2.0, PDFBox NOTICE и лицензии Bouncy Castle включены в APK в `assets/licenses/`. Лицензии Android SDK/JDK/Gradle относятся к инструментам сборки и не означают передачу прав на пользовательские PDF.

Сетевые функции Bouncy Castle не используются; приложение не запрашивает INTERNET. Android lint отмечает trust-manager классы внутри транзитивного `bcpkix`, но Readerka не создаёт сетевых клиентов и не использует эти классы. Проверки HTTPS при настройке сборочной среды включены.
