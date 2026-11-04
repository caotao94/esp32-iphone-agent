# Android port

The iPhone version needs three things from the phone: a way in (AirPlay screen mirroring), a way to act
(USB HID + AssistiveTouch) and a brain (the app). Android changes the first two, and the third is a rewrite.

```
iPhone:  iPhone --Screen Mirroring--> ESP32-S3 AirPlay receiver --BLE--> iPhone app --> vision model
            ^                            own hotspot, H.264                |
            +---------- USB HID relative mouse + AssistiveTouch <----------+

Android: Android --MediaProjection (in the app, no board)----------------> vision model
            ^                                                              |
            +---------- USB HID single-touch digitizer <-- BLE ----------- +
                                        ESP32-S3, no hotspot, no AirPlay
```

**Route A** (what this implements): keep the board as the hands, replace the eyes with the phone's own screen
capture, and rewrite the app in Kotlin. The board stays in the loop, so the agent's input is still real
physical HID input - no root, no ADB, no computer, and no accessibility permission.

Two things get strictly better on Android:

| | iPhone | Android |
|---|---|---|
| Eyes | AirPlay to the board, H.264 back over BLE: 480x1040, <=5 fps, board hotspot (no internet on Wi-Fi) | `MediaProjection` in the app: native resolution, tens of fps, the phone keeps its own Wi-Fi for the model |
| Hands | relative mouse + AssistiveTouch, so the app calibrates, re-homes, wiggles the pointer to find it and corrects the miss | single-touch digitizer (0-32767), finger down/move/up, no pointer calibration |
| Board Wi-Fi | hotspot + AirPlay receiver + coex tuning | off. BLE has the radio to itself; ~4.5 MB of PSRAM stays free |
| Leaving its own app | a private `suspend` call | `moveTaskToBack(true)` |
| Home / Back | consumer Home key, or edge swipes | consumer Home **and** Back keys |

## Status

Done and in this repository:

- **Firmware**: an Android build (`hardware/flash.sh --android`) with a single-touch USB digitizer, a `back` key,
  no hotspot and no AirPlay. The BLE protocol is unchanged, so [ble-protocol.md](ble-protocol.md) still applies.
- **`android/`**: a Kotlin/Compose app with the whole agent ported - BLE link, command channel, both pointer
  backends, `MediaProjection` capture, the planner/actor loop, the action grammar, the change detection,
  staged recovery, an OpenAI-compatible streaming client, traces in the iPhone layout, and a foreground
  service that keeps a run going while another app is in front.
- **Prompts**: Android variants in `android/app/src/main/assets/prompts/`.
- **`hardware/tools/board_test.py android`**: the M0 board checklist over BLE, from any computer.

The Android app (0.1.4) has been compiled locally on Windows with JDK 17 and Android SDK 35; JVM tests pass,
Lint has no errors, and a debug APK is generated. Both firmware variants compile with ESP32 core 3.3.12
and NimBLE-Arduino 2.5.1. **Real phone and board testing is still pending.** There is also no run
replay UI and no saved tasks/shortcuts/widgets yet. See [Windows development](windows-development.md)
for the local tools and build commands.

## M0: check the hardware before writing more app code

Everything above assumes four things about your phone. Check them with the board plugged in; `board_test.py`
drives the board from a computer over BLE (`uv run hardware/tools/board_test.py android`).

1. **The phone powers the board and is the USB host.** Some OEM builds (OPPO/OnePlus/realme/vivo) have an OTG
   switch that is off by default, or limit host current. With the Android firmware the board runs BLE only, so
   its current is much lower than in the iPhone build (no Wi-Fi).
2. **A touch lands.** New Android firmware uses a Touch Screen digitizer with a Finger contact identifier and count, not an absolute Mouse.
   `hidinfo` must report `pointer: touchscreen`. `abs 16384 16384` sets the next contact location without
   displaying a mouse cursor; `tap 16384 8192` touches the upper middle of the screen. The old absolute-mouse
   descriptor could leave Home/Back working while all clicks/swipes had no effect. Reflash the board and
   unplug/replug it after this change; updating only the APK cannot replace a USB descriptor. In the Android
   app's **Board > USB input diagnostics**, the board must appear as `ESP32 Agent Touch [touchscreen]` with
   X/Y `0..32767`; `[mouse]` or no ESP32 device means the phone still has the old enumeration.
3. **A drag is a drag.** `press`, `abs`, `release` should produce finger down/move/up.
   Check a list and a map. `SWIPE_MS` / `SWIPE_HOLD_MS` in
   `AbsGestures.kt` (and `HOLD` / `TAP_GAP` in `RelGestures.kt`) are the knobs.
4. **The system keys land.** `homescreen` -> Home, `back` -> Back (Android maps consumer AC Home / AC Back to
   KEYCODE_HOME / KEYCODE_BACK through the Linux HID layer).

Then check the capture side in the app:

5. **MediaProjection frames arrive** and cover the whole screen. Android 14+ explicitly requests **Entire screen**;
   a single-app capture ends as soon as the agent leaves that app. FLAG_SECURE windows (banking, DRM) come
   through black, like the iPhone's unmirrored screens.
6. **Is the mouse cursor in the capture?** Only the *relative* backend needs to see the pointer (to calibrate
   and to correct a miss). If your phone's capture does not include the cursor, `startCalibration` cannot find
   it and reports "the pointer was not found" - use the absolute backend, or add a manual one-point calibration
   (tap where the pointer is; `RelGestures.finishCalibration` already takes x/y).

## Protected apps

Android apps can set `FLAG_SECURE`, and DRM video surfaces can be protected by the display compositor. MediaProjection
returns a black or incomplete frame for those surfaces by design; a normal app cannot turn that back into pixels.
The agent detects several consecutive uniform dark frames and stops before sending the frame to the model, with a
message that the current app blocks capture. Use the app's own export, web, or approved screen-sharing feature, or
take over that step manually. If the app is yours, remove `FLAG_SECURE` only where its security policy permits it.
Root, Xposed, system-signed capture, and DRM hooks are device-specific ways of defeating that protection and are not
part of this app.
## Build and run

```bash
# firmware (board plugged into a computer)
hardware/flash.sh --android              # add --debug for a serial log, --build-only to just compile

# app (JDK 17 and Android SDK 35; Gradle 8.9 comes from the wrapper)
cd android
bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `.\gradlew.bat` for the app and `hardware/build.ps1 -Android` for the firmware.
To flash an existing build, connect the board's native USB data port to the PC and run
`hardware/flash.ps1 -Android -Port COM7` (replace COM7 with the actual board port; see
[Windows development](windows-development.md)). Disconnect it from the PC after flashing and reconnect
it to the phone's OTG/data port.

In the app: **Board** -> Scan -> tap the board (`ESP32 Agent XXXX`) -> **Model** -> base URL, key, actor model
(a vision model) -> **Run** -> type a task -> Start -> allow the screen capture -> the app moves itself to the
back and the agent works. The notification shows what it is doing; **Stop** is on the notification.

Named apps in a task are matched against the phone's installed launcher app labels. The planner and actor use
these exact names in the launcher's application-list search rather than paging through icons. They first inspect
the current screen and continue inside an already-open app. Clones with the same label are left for visual selection.

Control uses one action per screenshot, including typing and key presses. The next action is chosen from a new
observation; the agent drops a proposed action if the layout changed while the model was thinking. A pixel change
is feedback rather than proof of success or failure. Near-duplicate no-effect taps, repeated navigation/toggle
cycles, three taps without visible progress, and three replans without progress stop the run with a reason.
Static captures remain available while projection is active, and duplicate frames do not prolong the settle wait.

Set your own `applicationId` in `android/app/build.gradle.kts` before publishing anything.

## How the pieces line up

| iPhone (`ios/IPhoneAgent/`) | Android (`android/app/src/main/java/.../`) | Notes |
|---|---|---|
| `Board/BoardProtocol.swift` | `board/BoardProtocol.kt` | same UUIDs, `status` JSON, reply grammar |
| `Board/BoardLink.swift` | `board/BoardLink.kt` | CoreBluetooth -> `BluetoothGatt`; no `video` subscription |
| `Board/BoardCommands.swift` | `board/CommandChannel.kt` | same ids, timeouts, retry after a timeout, keep-awake |
| `Agent/JSON.swift` | `agent/Json.kt` + `agent/JsonParse.kt` | ordered objects, `json.dumps` formatting, no platform JSON library |
| `Agent/Actions.swift` | `agent/Actions.kt` | the grammar in `prompts/actor.md` |
| `Agent/Vision.swift` | `agent/Vision.kt` + `agent/VisionMath.kt` | the math is pure, so it runs in JVM tests |
| `Agent/AgentLoop.swift` | `agent/AgentLoop.kt` | planner/actor, multi-action replies, `changed` feedback, hiccups, help |
| `Agent/Pointer.swift` | `gestures/RelGestures.kt` | the relative backend: calibration, re-homing, aim correction |
| - (not needed on iOS) | `gestures/AbsGestures.kt` | the absolute backend |
| `Video/VideoFeed.swift` | `screen/CaptureScreen.kt` | `ScreenSource` is the same interface, so the loop did not change |
| `Services/LLMClient.swift` | `net/LlmClient.kt` | OpenAI-compatible, SSE streaming, token-parameter fallback |
| `Services/TraceStore.swift` | `store/TraceStore.kt` | same `steps.jsonl` / `NNN.jpg` layout as [run-data-export.md](run-data-export.md) |
| `App/RunSession.swift` + Live Activity | `AgentService.kt` + `AgentRuntime.kt` | a `mediaProjection` foreground service and a notification |
| `Agent/AssistiveTouchSpots.swift`, `App/TouchProbe.swift`, `App/AutoCalibrator.swift`, `App/ProbeFit.swift` | - | gone: an absolute pointer has no AssistiveTouch button to dodge and no drift to fit |

## Firmware changes

`hardware/hid_bridge/target.h` selects the build; `flash.sh --android` passes `-DIA_TARGET_ANDROID=1`.

- `hid_bridge.ino`: `USBHIDAbsoluteMouse` instead of the relative mouse; new `abs X Y`, `tap X Y [HOLD_MS]`,
  `swipe X1 Y1 X2 Y2 [MS] [HOLD_MS]` (X/Y in 0-32767); `back` (AC Back, in both builds); `home` is a no-op and
  `move` is refused; `scroll` keeps the pointer where it is.
- `netcfg.cpp`: no Wi-Fi at all - no SoftAP, no station, no coex tuning. `net` answers `{"mode":"none"}`.
- `video_link.cpp`: no GOP buffer, no forwarding; the `video` commands answer `err no video`.
- The iPhone build is untouched: every change is behind `#if IA_TARGET_ANDROID`, and the default build compiles
  the same firmware as before.

`src/airplay/` (GPL-3.0, ported from UxPlay) is still compiled but never called in an Android build, so
unreferenced sections are dropped by `--gc-sections`. For a binary that provably contains none of it, copy the
sketch to a scratch directory without `hardware/hid_bridge/src/airplay/` and build that; nothing in the Android
code path references it.
## Android specifics that bite

- **Consent per session.** Android 14+ gives one MediaProjection token per consent, requires
  `FOREGROUND_SERVICE_MEDIA_PROJECTION`, and the service has to be started from the foreground. That is why
  `MainActivity` asks for the capture and `AgentService` creates the projection, in that order.
- **Choose "Entire screen".** A single-app share ends the moment the agent leaves that app; the run then sits
  in staged recovery ("waiting for the screen capture") and gives up after `Recovery.LIMIT`.
- **Background start rules.** The service is started with `startForegroundService` from the activity; a run
  cannot be started from the background on Android 12+.
- **The API key.** It lives in the app's private `SharedPreferences` here. The iPhone app uses the Keychain;
  move it to a Keystore-backed store before this leaves your phone.
- **Notifications.** Without `POST_NOTIFICATIONS` (Android 13+) the run still works, you just do not see the
  progress or the Stop button.
- **Text is pasted, in any language.** The app places the text in the Android clipboard (marked sensitive,
  so it is not previewed) and the board presses `Ctrl+V`. Keys typed on the HID keyboard would go through
  the phone's input method, and a Chinese (pinyin) keyboard turns letters into a composition, so ASCII is
  pasted too; the HID keyboard types it only if the clipboard refuses the text. The target field must accept
  paste: fields that block it (some banking or one-box-per-digit code fields) still need manual input.
- **Portrait only.** Coordinates assume portrait, as the iPhone version does, and a rotation mid-run is not
  handled: the capture size is chosen when the run starts.

## Gaps

In rough order of usefulness:

1. Run it on a real phone and fix what the M0 list predicts wrongly (pointer semantics, cursor in the capture,
   OEM USB quirks).
2. Run replay: traces are written in the iPhone layout, but there is no UI to page through them yet.
3. Saved tasks and shortcuts (App Shortcuts, or an intent filter), so a task can start without typing it.
4. Manual one-point calibration, for a phone whose capture hides the cursor.
5. Latency: with a local capture the "wait for the screen to settle" tuning can be much tighter than the
   AirPlay-over-BLE timings it inherited.
6. CI is wired (`.github/workflows/ci.yml`: an `android` job, and the firmware job builds `--android` too);
   confirm the hosted Linux builds as well as the local Windows checks.
7. Landscape, a Keystore-backed key, binary launcher icons for APIs below 26.

## Why not the alternatives

- **No hardware (an AccessibilityService injecting gestures).** Much easier, and it is what most Android agents
  do, but it gives up what this project is: real physical input that no app can tell from a finger, no
  accessibility permission, and nothing to detect. It is a different project, not a port of this one.
- **Miracast / wireless display into the board.** Android has no AirPlay source, and an ESP32-S3 is not a
  realistic Miracast sink. Capturing on the phone is better anyway: no hotspot, no 5 fps ceiling.
- **BLE HID instead of USB.** Possible (Android supports HOGP), and it would make the board wireless, but then
  it needs its own battery and pairing is fiddlier. A good follow-up once the USB path is proven.

## License and credit

Same as the repository: MIT, except `hardware/hid_bridge/src/airplay/` (GPL-3.0, not used by an Android build).
If you take this Android port into another project, keep the MIT copyright notice
(`Copyright (c) 2026 Henry Yuan`) and credit
**[ESP32 iPhone Agent by Henry Yuan](https://github.com/onehumanbeing/esp32-iphone-agent)** in your README and
in any public demo, video or post. See [NOTICE](../NOTICE) and [AGENTS.md](../AGENTS.md).
