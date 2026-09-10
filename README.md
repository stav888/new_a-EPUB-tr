# EPUBTranslator

EPUBTranslator is a local Android EPUB reader for books that users import from their own devices. It has no built-in catalog and does not distribute books or translated books.

## Translation

Paragraph translation is Google ML Kit on-device translation. After the selected language model is downloaded over Wi-Fi, translation can work offline. Accuracy is not guaranteed and translations are not official or human translations.

The app does not upload book text to a developer translation server. Translation is available on the reading screen for selected paragraphs only. Credits are currently a local UI counter; this project does not include billing, subscriptions, or paid checkout.

## Sharing and storage

Reading data, cached translations, and imported EPUBs are stored locally. Sharing uses Android's share sheet for the original imported EPUB file only. The app does not export or share translated books and does not host public files.

## Build

1. Install Android Studio or use the included Gradle wrapper.
2. Create `local.properties` with the local Android SDK path.
3. Build with `gradlew.bat :app:assembleDebug` on Windows or `./gradlew :app:assembleDebug` on macOS/Linux.
	For an install that automatically increments `BUILD_NUMBER` and `VERSION_CODE`, use `gradlew.bat :app:installDebug`.
4. Use the app's Settings screen to download only the language models you need, preferably over Wi-Fi.

Users are responsible for importing and translating only EPUB files they have the right to use.
