# Readerka Android 1.0.0 — проверочные APK

Ветка содержит только файлы для скачивания. Main, исходники Windows и Windows-релизы не изменены.

- readerka-1.0.0-dev.apk — org.readerka.
- readerka-1.0.0-preview.apk — org.readerka.preview.
- Android API 28+, arm64-v8a/x86_64; debug-подпись облачной среды.

Если Readerka уже установлен, несовпадение подписи может запрещать обновление. Не удаляйте приложение с личной библиотекой; используйте свободный Preview или отдельное тестовое устройство.

Исходники, лицензии, полные native source archives и инструкции сборки этой версии: https://github.com/rampe22mesler-dot/readerka/tree/android-v1.0.0-preview

APK содержит GPL-компонент DjVuLibre; соответствующие Android/JNI/build исходники и полные исходники нативных библиотек доступны в указанном теге, включая app/src/main/cpp и windows/third-party.

49 Android unit-тестов и 68 общих тестов прошли. Отдельно проверены GUI, курсор и сохранение над реальной IME. Полный набор 53 целиком не повторялся; физический Huawei/ARM64 и реальный Dropbox не проверены.
