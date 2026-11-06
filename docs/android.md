# Android port

The iPhone version needs three things from the phone: a way in (AirPlay screen mirroring), a way to act
(USB HID + AssistiveTouch) and a brain (the app). Android changes the first two, and the third is a rewrite.

```
iPhone:  iPhone --Screen Mirroring--> ESP32-S3 AirPlay receiver --Wi-Fi TCP--> iPhone app --> vision model
            ^                            own hotspot, H.264                      |
            +---------- USB HID relative mouse + AssistiveTouch <-- BLE ---------+

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
| Eyes | AirPlay to the board, which forwards the H.264 to the app over Wi-Fi (TCP 7201): 480x1040, <=5 fps by default, board hotspot (no internet on Wi-Fi) | `MediaProjection` in the app: native resolution, tens of fps, the phone keeps its own Wi-Fi for the model |
| Hands | relative mouse + AssistiveTouch, so the app calibrates, re-homes, wiggles the pointer to find it and corrects the miss | single-touch digitizer (0-32767), finger down/move/up, no pointer calibration |
| Board Wi-Fi | hotspot + AirPlay receiver, video to the app (TCP 7201), firmware updates (TCP 7300), the CLI's Wi-Fi link (TCP 7202), coex tuning | off. BLE has the radio to itself, and the ~5 MB of video buffers in PSRAM are never allocated |
| Leaving its own app | a private `suspend` call | `moveTaskToBack(true)` |
| Home / Back | consumer Home key, or edge swipes | consumer Home **and** Back keys |

## Status

Done and in this repository:

- **Firmware**: an Android build (`hardware/flash.sh --android`) with a single-touch USB digitizer, a `back` key,
  and no Wi-Fi at all (no hotspot, AirPlay, Wi-Fi video, OTA or CLI Wi-Fi link). It speaks the same BLE
  protocol; the commands it adds or refuses are listed in [ble-protocol.md](ble-protocol.md#android-builds).
- **`android/`**: a Kotlin/Compose app with the whole agent ported - BLE link, command channel, both pointer
  backends, `MediaProjection` capture, the planner/actor loop, the action grammar, the change detection,
  staged recovery, an OpenAI-compatible streaming client, traces in the iPhone layout, and a foreground
  service that keeps a run going while another app is in front.
- **Prompts**: Android variants in `android/app/src/main/assets/prompts/`.
- **`hardware/tools/board_test.py android`**: the M0 board checklist over BLE, from any computer.

The Android app (0.1.5) has been compiled locally on Windows with JDK 17 and Android SDK 35; JVM tests pass,
Lint has no errors, and a debug APK is generated. Both firmware variants (board firmware 0.2.3) compile with
ESP32 core 3.3.12 and NimBLE-Arduino 2.5.1. The app has run on one phone so far (nubia RedMagic, Android 16)
with the touchscreen firmware from before the 0.2.3 merge: a full shopping-app-to-WeChat sharing task succeeded
end-to-end (taps, swipes, Chinese and emoji paste, opening an app from the notification shade's search), and the
Home key worked in a separate earlier run. The merged 0.2.3 Android firmware compiles but has not been flashed
to a board yet. There is also no run replay UI and no saved tasks/shortcuts/widgets yet. See
[Windows development](windows-development.md) for the local tools and build commands.

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
Launchers without an app drawer (RedMagic and other nubia phones) keep app search in the notification shade, and
the prompts describe that route. Both models are also told the phone's maker, model and Android version.

Control uses one action per screenshot, including typing and key presses. The next action is chosen from a new
observation; the agent drops a proposed action if the layout changed while the model was thinking. A pixel change
is feedback rather than proof of success or failure. Near-duplicate no-effect taps, repeated navigation/toggle
cycles, three taps without visible progress, and three replans without progress stop the run with a reason.
Going back and forth between the same screens (open the shade, Home, open it again) is caught from the moves and
the screen layout, since a ticking clock makes every frame differ: the first two such round trips only warn the
actor, the third stops the run, and a round trip more than ten moves after the previous one starts the count again.
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

`hardware/hid_bridge/target.h` selects the build; `flash.sh --android` (`build.ps1 -Android` on Windows) passes
`-DIA_TARGET_ANDROID=1`.

- `hid_bridge.ino`: the pointer is an `AndroidTouch` (`android_touch.h`), a single-touch USB digitizer, instead
  of the relative `USBHIDMouse`, and the USB device is named `ESP32 Agent Touch` (revision 0x0104). New commands:
  `abs X Y`, `tap X Y [HOLD_MS]` and `swipe X1 Y1 X2 Y2 [MS] [HOLD_MS]` (X/Y in 0-32767). `home` answers `ok`
  without moving (an absolute pointer does not drift); `move` answers `err relative move needs the iphone build;
  use abs X Y`, and `scroll` answers `err touchscreen has no wheel; use swipe X1 Y1 X2 Y2`. A failed touch report
  lifts the finger before the command answers `err`.
- Also in `hid_bridge.ino`: `board()` refuses `stream`, `snap`, `bench`, `vdbg`, `res`, `fps`, `coex` and
  `txpower` (`err unsupported in the android build`), and a CLI `net` write (`err no Wi-Fi in the android build
  (the CLI stays on Bluetooth)`). `setup()` skips `wv_begin()`, `ota_begin()`, `cli_tcp_begin()` and the AirPlay
  receiver: no Wi-Fi is started, so lwIP never comes up, and those would open sockets on a stack that does not exist.
- `netcfg.cpp`: only the NVS settings and the board id; no SoftAP, no station, no coex tuning. `net` reads
  `{"mode":"none","ssid":"","pass":"","ip":""}` (no `vtok`), and a write to the `net` characteristic (or `0 net`
  over USB serial) is accepted and dropped.
- `video_link.cpp`: stubs. The GOP, tail and scratch buffers (~5 MB of PSRAM) are never allocated and the video
  stats stay zero; `board()` refuses `stream`, `snap` and `bench` before they get here.
- Both builds: `back` (consumer AC Back), `hidinfo` (target, pointer type, USB state and readiness, build time;
  the Android build adds its sent/failed touch-report counters), and an `err` reply when a consumer report fails.
  The iPhone build answers `err needs the android build` to `abs`, `tap` and `swipe`. Everything else that is
  Android-specific is behind `#if IA_TARGET_ANDROID`.

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
  so it is not previewed) and the board presses `Ctrl+V`; the clip is cleared once the screen has settled after
  the paste, and when a run ends. Keys typed on the HID keyboard would go through the phone's input method, and
  a Chinese (pinyin) keyboard turns letters into a composition, so ASCII is pasted too. Plain digits are the
  exception: they are typed key by key, which one-box-per-digit code fields and number pickers need. The HID
  keyboard also types ASCII when the clipboard refuses the text. The target field must accept paste: fields
  that block it (some banking apps) still need manual input, and terminal apps (Termux) read `Ctrl+V` as a
  control character rather than a paste.
- **Portrait only.** Coordinates assume portrait, as the iPhone version does, and a rotation mid-run is not
  handled: the capture size is chosen when the run starts.

## Gaps

In rough order of usefulness:

1. Flash the merged 0.2.3 Android firmware and check it with `hardware/tools/board_test.py android` (the phone
   run used the firmware from before the merge), then test more phones than the one so far (nubia RedMagic,
   Android 16) and fix what the M0 list predicts wrongly (OTG power, pointer semantics, cursor in the capture,
   OEM USB quirks).
2. The round-trip guard can mistake working through a list for a loop: scrolling by the same amount, tapping
   the same spot and going Back for each item repeats the same three moves on screens whose layout does not
   change, so the guard warns and then stops the run after a few items.
3. Add `esp32agent` CLI and MCP server support for the Android firmware (a session without `stream on`, using
   `abs`/`tap`/`swipe` instead of `home`/`move`).
4. Run replay: traces are written in the iPhone layout, but there is no UI to page through them yet.
5. Saved tasks and shortcuts (App Shortcuts, or an intent filter), so a task can start without typing it.
6. Manual one-point calibration, for a phone whose capture hides the cursor.
7. Latency: with a local capture the "wait for the screen to settle" tuning can be much tighter than the
   AirPlay-over-BLE timings it inherited.
8. CI is wired (`.github/workflows/ci.yml`: an `android` job, and the firmware job builds `--android` too);
   confirm the hosted Linux builds as well as the local Windows checks.
9. Landscape, a Keystore-backed key, binary launcher icons for APIs below 26.

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
