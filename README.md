# RunHelper

Android-приложение отслеживает пробежку в фоновом режиме и с заданным интервалом голосом сообщает прошедшее время и средний темп.

## Открытие в Android Studio

1. Запустите Android Studio 2025.3.2.
2. Выберите **Open** и укажите папку проекта — ту, где находится файл `settings.gradle`.
3. Дождитесь синхронизации Gradle. В проекте используется Gradle Wrapper и Android Gradle Plugin.
4. Если Android Studio предложит установить Android SDK Platform 36.1, установите её.

Локальный путь к SDK хранится в `local.properties`. Этот файл не входит в общие настройки проекта, поскольку путь к SDK может отличаться на других компьютерах.

## Сборка в Windows

Запустите один из файлов через Проводник или командную строку:

- `build-debug.bat` — собирает `app/build/outputs/apk/debug/app-debug.apk`.
- `build-release.bat` — собирает `app/build/outputs/apk/release/app-release-unsigned.apk`.

Debug APK подписан отладочным ключом Android. Release APK не подписан. Перед распространением подпишите его в Android Studio через **Build > Generate Signed Bundle / APK**.

