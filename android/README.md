# ESP32 Agent for Android

The Android half of this project: the same ESP32-S3 board is the hands, the phone's own screen capture is the
eyes, and the agent (planner + actor over any OpenAI-compatible vision endpoint) runs on the phone.

**Read [docs/android.md](../docs/android.md) first**: what changed versus the iPhone app, the hardware checklist
to run before trusting anything here, the file-by-file mapping from `ios/IPhoneAgent/`, and the list of gaps.
So far it has run on one phone (nubia RedMagic, Android 16); check yours against the M0 list there.

## Build

Needs JDK 17 and Android SDK 35. The included wrapper uses Gradle 8.9.
For the configured Windows environment on E:, see [Windows development](../docs/windows-development.md).

```bash
bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `.\gradlew.bat` in PowerShell instead of `bash ./gradlew`.

Set your own `namespace` / `applicationId` in `app/build.gradle.kts` before publishing anything.

## Use

1. Flash the board with `hardware/flash.sh --android` and plug it into the phone (USB-C, the phone powers it).
2. **Board** tab: Scan, tap `ESP32 Agent XXXX`. The status line should read `usb: ok`.
3. **Model** tab: base URL, API key, an actor model that accepts images; **Test** it.
4. **Run** tab: type a task, **Start**, allow the screen capture and choose **Entire screen**. The app moves
   itself to the back and the agent works; the notification shows each step, and **Stop** ends the run.

A board with the stock (iPhone) firmware also works: the app asks it for `abs`, gets an error, and falls back to
the relative pointer, which needs calibrating (**Board** tab, then keep the screen still).

## Layout

```
app/src/main/java/com/example/esp32agent/android/
  agent/     Json, JsonParse, Actions, Vision(+Math), AgentLoop, Recovery, Prompts, StepText
  board/     BoardProtocol, BoardLink (BLE GATT), CommandChannel, Errors
  gestures/  Gestures, AbsGestures (Android firmware), RelGestures (stock firmware, calibrated)
  screen/    ScreenSource, CaptureScreen (MediaProjection)
  net/       ChatModel, LlmClient (OpenAI-compatible, SSE)
  store/     Settings, TraceStore (the iPhone trace layout)
  ui/        Theme, AppUi
  AgentApp, AppGraph, AgentRuntime, MainActivity, AgentService
app/src/main/assets/prompts/   planner.md, actor.md, app-rules.md (Android variants of ../../prompts/)
app/src/test/                  JVM tests for the pure logic
```

## License

MIT, like the rest of the repository; see [LICENSE](../LICENSE) and [NOTICE](../NOTICE). The Android firmware
build never calls the GPL-3.0 AirPlay code under `hardware/hid_bridge/src/airplay/`.
