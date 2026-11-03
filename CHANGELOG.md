# Changelog

## Unreleased (main)

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
