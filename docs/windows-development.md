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

Verified locally on November 6, 2026, after merging board firmware 0.2.3:

- Android 0.1.5: 92 JVM tests pass; Lint has no errors; debug APK builds successfully.
- Android firmware: 781,643 bytes of flash and 82,240 bytes of static RAM.
- iPhone firmware: 1,490,015 bytes of flash and 113,700 bytes of static RAM.
- Firmware host tests: not run this time. They now link mbedtls (`-lmbedcrypto`), and no C toolchain was
  on `PATH`; CI runs them.
- `hardware/tools/board_test.py`: Python syntax check passes.

On November 4 (Android 0.1.4, before the merge) the host tests passed with Zig 0.14.1 and undefined-behavior checks.

Hardware: the Android app has run on one phone (nubia RedMagic, Android 16) with the touchscreen firmware from
before the merge. The merged 0.2.3 Android firmware compiles but has not been flashed to a board yet.

## Flash the Android touchscreen firmware

The new Android build identifies as `ESP32 Agent Touch` (USB revision 0x0104). Earlier Android builds
used an absolute Mouse descriptor that can leave keyboard Home working but clicks/swipes ignored.
The fix is in the board firmware; installing the APK alone does not update it.

1. Connect the board's native USB data port to the PC. A charging-only cable or UART-only connector will
   not provide HID input to the phone later.
2. Open PowerShell in the repository, load the E: environment, and identify the board port:

```powershell
. E:\DevTools\esp32-agent-env.ps1
arduino-cli --config-file E:\Arduino\arduino-cli.yaml board list
# Replace COM7 with the board's actual port. This uploads the existing Android build.
.\hardware\flash.ps1 -Android -Port COM7 -ConfigFile E:\Arduino\arduino-cli.yaml
```

If upload cannot enter the bootloader, hold BOOT, tap RESET, release BOOT, check the COM port again,
then retry with that port. The script does not guess a device and can be previewed with `-WhatIf`.

3. Disconnect the board from the PC and plug its native USB port into the unlocked Android phone (OTG).
4. Install the APK. In **Board > USB input diagnostics**, use **Read HID info** and **Scan Android input devices**.
   The board should appear as `ESP32 Agent Touch` with `[touchscreen]` and X/Y `0..32767`; a normal touchscreen does not
   display a mouse cursor. **Test Tap** sends a center tap without starting an agent run.
5. Run a simple task in Settings. `sent` should increase; `failed` should stay zero. Counters confirm
   USB transfer completion, so the actual tap/drag result still needs observation on the phone.

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
