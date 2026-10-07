# MoodMail

An Android app that reads your Gmail inbox and scores how each email feels, using a Liquid AI model that runs on the phone through the Zetic Melange SDK. Once Gmail hands over a message, the analysis happens on the phone. It also reads your own reply as you type it and tells you to slow down if you're answering after 5 pm or the reply reads as heated.

The interface is a throwaway debug screen. The real UI comes later.

## What it does

When you open the app and tap Analyze inbox, it signs you into Gmail with read-only access, pulls your five newest inbox messages and labels each one with an emotion (anger, anxiety, calm, gratitude and so on), a tone (hostile, curt, passive-aggressive, warm and so on), an intensity from 0 to 10 and a heated flag. Heated emails trigger a notification.

Type a reply in the draft box and it scores the draft about a second after you stop typing. After 5 pm (or before 6 am) you get a gentle nudge to save it for the morning. If the draft or the email you're answering is heated, the warning gets firmer. During the day only a heated draft triggers anything.

Every 15 minutes a background job checks for new mail and notifies you about heated messages it hasn't seen before. It only works after you've signed in once from the app.

An experimental probe also sends a single image to the vision model and times the answer, to see whether reading your facial expression while you draft is fast enough to be worth building. No camera is wired up yet.

## Models

Text analysis uses `zetic/LFM2.5-1.2B-Instruct`. The vision probe uses `zetic/LFM2.5-VL-450M`. Both download on first run. The tone-reading prompt lives in `app/src/main/java/com/jerush/moodmail/llm/MoodPrompts.kt` if you want to tune it.

## Setup

You need a physical Android phone (Android 8 or newer) with USB debugging on. The Melange SDK does not run on emulators.

1. Create a Personal Access Token at https://melange.zetic.ai/settings?tab=pat (new accounts can use invitation code `HOUSTON2026`).
2. Create `local.properties` in the project root. It is gitignored, so the token never gets committed:

   ```
   sdk.dir=/path/to/Android/sdk
   melange.personalKey=YOUR_TOKEN
   ```

3. In Google Cloud Console, enable the Gmail API. Set the OAuth consent screen to External in Testing mode, add the `gmail.readonly` scope and add your Gmail address as a test user.
4. Create an Android OAuth client with package name `com.jerush.moodmail` and your debug keystore's SHA-1, which this prints:

   ```sh
   keytool -list -v -keystore ~/.android/debug.keystore \
     -alias androiddebugkey -storepass android -keypass android | grep SHA1
   ```

   The keystore is created the first time you build. Each machine has its own, so register each one you build from.

## Build and run

Use JDK 17 or newer. Android Studio's bundled one works:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug
```

The Melange SDK is pinned to 1.11.0 and comes from Zetic's GitHub release through an Ivy repository declared in `settings.gradle.kts`, not from Maven Central.

## Status

The app compiles and the 63 unit tests pass (the email parser, the model output parser, the 5 pm rules and the image conversion). It has not run on a phone yet, so model loading, Gmail sign-in and notifications are unverified.

Known limits: Gmail tokens from a Testing-mode consent screen expire after about an hour, so the background check stops finding mail until you open the app again. The tone prompt is around 1,000 tokens and its speed on a phone hasn't been measured.
