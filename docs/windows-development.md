# Windows development

The local Windows setup used for this project keeps the large SDKs and caches on E:.
The repository itself can stay under `C:\Users\CT\Desktop\ctbot\esp32-iphone-agent`.

Installed locations:

- JDK 17: `E:\DevTools\jdk-17`
- Gradle 8.9: `E:\DevTools\gradle-8.9`
- Android SDK: `E:\Android\Sdk`
- Gradle and Android user caches: `E:\DevTools\gradle-home` and `E:\Android\.android`
- Arduino CLI data and libraries: `E:\Arduino`
- `adb`: `E:\Android\Sdk\platform-tools\adb.exe`

Arduino uses ESP32 core 3.3.12 and NimBLE-Arduino 2.5.1. Its configuration is
`E:\Arduino\arduino-cli.yaml`; downloads, packages and libraries all stay on E:.

The user environment has `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME`,
and the tool directories on `PATH`. Existing PowerShell windows need to be reopened to see them.
For a current shell, run:

```powershell
. E:\DevTools\esp32-agent-env.ps1
```

Build the Android app and run its checks:

```powershell
cd C:\Users\CT\Desktop\ctbot\esp32-iphone-agent\android
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The APK is written to `android\app\build\outputs\apk\debug\app-debug.apk`.
A copy of the verified APK is at `E:\Android\artifacts\esp32-agent-debug.apk`.

Build the firmware without flashing a board:

```powershell
cd C:\Users\CT\Desktop\ctbot\esp32-iphone-agent
. E:\DevTools\esp32-agent-env.ps1
.\hardware\build.ps1
.\hardware\build.ps1 -Android
```

Firmware output is under `E:\Arduino\build\iphone` or `E:\Arduino\build\android`.
The binaries to flash are in each target's `firmware` subdirectory.

Verified locally on November 4, 2026:

- Android 0.1.1: 43 JVM tests pass; Lint has no errors; debug APK builds successfully.
- Android firmware: 657,767 bytes of flash and 67,520 bytes of static RAM.
- iPhone firmware: 1,455,631 bytes of flash and 94,316 bytes of static RAM.
- Firmware host tests: pass with Zig 0.14.1 and undefined-behavior checks.
- `hardware/tools/board_test.py`: Python syntax check passes.

Hardware validation still requires an ESP32-S3 board and an Android phone. The build does not prove USB
host power, absolute pointer behavior, MediaProjection coverage, or consumer Home/Back key behavior.

## Optional coding-agent plugin (iPhone)

The upstream Claude Code / Cursor plugin and `skills/handsy/` instructions are included in this checkout.
They expose the `esp32agent` CLI as an MCP server so a desktop coding agent can see and operate an iPhone.
This workflow requires board firmware v0.2.2 or later, Bluetooth, Node.js 18 or later (`npx`), and `ffmpeg`.
The local Windows machine already has Node.js 24 and `ffmpeg`; these tools are separate from the Android SDK.

For the first CLI download, keep the npm cache on E: in the current PowerShell session:

```powershell
$env:npm_config_cache = 'E:\DevTools\npm-cache'
npx.cmd -y esp32agent version
```

In Claude Code, install the upstream plugin with:

```text
/plugin marketplace add onehumanbeing/esp32-iphone-agent
/plugin install esp32agent@handsy
```

Other MCP clients can use the server command in [the Handsy instructions](../skills/handsy/SKILL.md).
Installing a plugin does not update board firmware or pair a device.

The Android app runs on the phone and captures its screen with `MediaProjection`. Its Android firmware
has no AirPlay/video feed for this MCP server, so installing the iPhone plugin does not add desktop control
of Android. It is optional and is not needed to build or test the Android port.
