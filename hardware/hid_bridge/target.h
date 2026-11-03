// Which phone the board is plugged into. Build-time only; set from the command line (flash.sh --android
// adds -DIA_TARGET_ANDROID=1). The default (0) is the iPhone build this project shipped with.
//
// Android build (-DIA_TARGET_ANDROID=1):
//   - the USB pointer is absolute (0-32767 of the screen), which Android accepts and iOS ignores, so the
//     app needs no AssistiveTouch, no pointer calibration and no drift correction;
//   - `back` (consumer AC Back -> KEYCODE_BACK) joins `homescreen` (AC Home -> KEYCODE_HOME);
//   - no AirPlay receiver and no hotspot: the Android app captures the screen itself (MediaProjection)
//     and the phone keeps its own Wi-Fi for the model. The board never starts a radio network, which
//     leaves the whole radio to BLE and keeps ~4.5 MB of PSRAM free.
//   See docs/android.md. The AirPlay sources under src/airplay/ are not called by this build; they are
//   still compiled, so exclude that folder if you need a binary without the GPL-3.0 port.
#pragma once
#ifndef IA_TARGET_ANDROID
#define IA_TARGET_ANDROID 0
#endif