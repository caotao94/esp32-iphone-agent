# Changelog

## Unreleased (main)

- Android 0.1.5: stop back-and-forth navigation loops (repeated shade-open/Home-press or similar cycles); paste ASCII text too, so a Chinese (pinyin) keyboard cannot turn typed letters into a composition (HID typing only if the clipboard refuses); match apps whose launcher names only partially match the goal or appear next to Chinese text; tell the model which phone/launcher it's driving; don't discard a chosen "wait" as stale while a page loads.
- Android 0.1.4: look up launcher-visible installed app labels and use exact names in application-list search; continue inside an already-open app.
- Android control: execute one action per screenshot, discard coordinates when the layout changes during model inference, retain action history across replans, block near-duplicate and cyclic taps, and stop repeated replanning. Capture always requests the full display and static images stay usable while projection is active.
- Android input: support Chinese, emoji and other Unicode text by placing it in the Android clipboard and pasting with `Ctrl+V`.
- Android agent: prevent duplicate actions after a successful touch or an uncertain board timeout. Screen-change checks now handle unsigned grayscale pixels, preserve absolute-touch feedback, use the capture timestamp, and never replay a command whose reply was lost.
- Android capture: detect several consecutive uniform dark frames from protected/DRM windows and stop before
  sending an unusable frame to the model, with an actionable notice. Android's secure-surface policy cannot be bypassed by a normal app.
- Android 0.1.3 / touchscreen firmware: replace the absolute-mouse descriptor with a USB single-touch
  digitizer (Tip Switch, Contact ID/count and absolute X/Y), propagate failed touch/consumer reports as errors,
  bump the USB revision to 0x0104, add `hidinfo`, and expose HID diagnostics in the Board tab. Reflashing the board is required for the input change.
- Android 0.1.2: wait for the first captured frame before running or calibrating, report capture startup
  timeout/revocation, clear frames between sessions, and keep task input when switching tabs.
- Android model setup: strip clipboard whitespace/invisible marks from API keys (including saved settings),
  mask the key field, and reject invalid header characters without exposing the key. Debug app version 0.1.1.
- Android port (route A: `android/` + `docs/android.md`): a Kotlin/Compose app with the agent, the BLE link and
  the action grammar ported from the iPhone app. The eyes are a `MediaProjection` capture instead of AirPlay over
  BLE, and traces keep the iPhone layout. Firmware: `flash.sh --android` builds an absolute-pointer variant with
  no hotspot and no AirPlay, and both builds learned a `back` key (consumer AC Back). Not yet run on hardware.
- `hardware/tools/board_test.py android` checks that firmware build over BLE.
- No computer: the ESP32 is the AirPlay receiver (ported from UxPlay) with its own hotspot, and talks to a new
  iPhone app over BLE. The app is the agent: planner/actor loop, pointer calibration, run replay, Live Activity,
  widgets, Siri and Shortcuts, self-healing when the board, hotspot or mirroring drops.
- Prompts moved to `prompts/`.
- Product name: ESP32 Agent.
- `server/` is legacy and no longer needed.

## v0.1.0

- Computer-side version: UxPlay + Python server + web UI; the ESP32 is a USB HID bridge over Wi-Fi.
