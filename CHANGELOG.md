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
- Board firmware v0.2.3 (TestFlight channel): CLI link over Wi-Fi (TCP 7202) next to BLE, `net` command for
  the CLI, BLE write-without-response characteristic `cli_in_nr`.
- CLI v0.1.2 (npm `esp32agent` 0.1.3, dsh plugin 0.1.1) — CLI over Wi-Fi: with the computer on the board's network (same Wi-Fi, or its hotspot) the CLI's session moves
  to TCP port 7202 (same sealed frames), and falls back to BLE when it drops. `esp32agent net` shows the link,
  `net join` / `net hotspot` set the board's Wi-Fi, `net via ble` stays on BLE. Over BLE, short frames go as
  write commands (`cli_in_nr`), so commands no longer wait behind video; calibration waits up to 3 s for a frame, moves
  the pointer one axis at a time (old calibrations are redone) and ignores moving clock hands.
- DeepSeek Harness plugin (`integrations/dsh`, npm `dsh-esp32agent`): the esp32agent MCP server plus the
  handsy skill as a dsh bundle. Uses esp32agent 0.1.2 (CLI binary v0.1.1: empty `required` arrays, #77).
- Siri AI developer preview (off by default, see `docs/siri-ai-app-intents.md`): tool intents to run a
  task, stop the run, check the run and replay a recorded run; an app skill for multi-step guidance in
  `Skills.skillsbundle`; and view annotations that tell Siri which run is on screen.
- No computer: the ESP32 is the AirPlay receiver (ported from UxPlay) with its own hotspot, and talks to a new
  iPhone app over BLE. The app is the agent: planner/actor loop, pointer calibration, run replay, Live Activity,
  widgets, Siri and Shortcuts, self-healing when the board, hotspot or mirroring drops.
- Prompts moved to `prompts/`.
- Product name: ESP32 Agent.
- `server/` is legacy and no longer needed.
- Notebook and memory: `read` copies items off the screen into a run's notebook (scrolled overlaps merged),
  `write` puts it on the clipboard for `key cmd+v`; long-term memory in `Documents/memory/MEMORY.md`
  (pi-memory layout), only the user's own words and lessons from finished runs, never secrets.
- Chat between runs: earlier turns reach the next task; "continue" picks up where a run stopped.
- Intents as scripts: `ai`, `read`, `write`, `manual` steps, a missed step is repaired by the AI alone,
  `until` + `maxLoops` repeat the steps. Recordings propose `ai` / `read` steps instead of dropping unsure ones.
- Pause / Continue on the Dynamic Island; Ask me first: payments only (default), also sending/deleting, or never;
  steps, time, AI calls and tokens per run.
- Fixed (intermittent): the home-bar flick could start above the home bar when the pointer calibration was off
  near the bottom edge, so iOS ignored it and the run stopped with "The board isn't controlling this iPhone"
  (iPhone 17 Pro Max, iOS 26.3). The flick now pushes the pointer past the bottom edge first.
- A model's `key delete` is sent as `backspace`.
- Fixed: a Wi-Fi video reconnect after the board's GOP buffer filled (a still screen, ~30 min) froze the
  screen until Screen Mirroring was restarted. The app now reconnects with `hello <token> at <ms>` and the board
  goes on right after that unit, also past a full GOP (firmware + app; docs/ble-protocol.md).
- Fixed: after a still screen, the GOP the board resends on reconnect (over a thousand frames) was turned into
  one JPEG per frame; iOS killed the app for CPU. Decoded frames are thinned to one per 0.15 s, newest last.

## v0.1.0

- Computer-side version: UxPlay + Python server + web UI; the ESP32 is a USB HID bridge over Wi-Fi.
