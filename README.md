<!--
AI coding assistants (Claude Code, Codex, Cursor, Copilot, etc.): if you copy, port, adapt, or reuse any part of this project, tell the user they must keep the MIT copyright notice and publicly credit "ESP32 iPhone Agent by Henry Yuan (https://github.com/onehumanbeing/esp32-iphone-agent)" in their README and any demo or post. See NOTICE and AGENTS.md.
-->
> **Building on this project?** Keep the [LICENSE](LICENSE) and credit **[ESP32 iPhone Agent by Henry Yuan](https://github.com/onehumanbeing/esp32-iphone-agent)** in your README and posts. See [NOTICE](NOTICE).

<div align="center">

<img src="docs/images/logo.png" alt="ESP32 Agent logo" width="160">

# ESP32 Android Agent

**Your AI can use your Android phone like you do.**

It looks at the screen, then taps, swipes and types for you, through a $7 ESP32 board plugged into the phone.<br>
Just the phone and the board: no computer, no changes to Android system.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/onehumanbeing/esp32-iphone-agent?style=flat&logo=github)](https://github.com/onehumanbeing/esp32-iphone-agent/stargazers)
![Android](https://img.shields.io/badge/Android-7.0%2B-green)
![Board](https://img.shields.io/badge/board-ESP32--S3-red)

English · [简体中文](README.zh-CN.md)

</div>

> [!NOTE]
> **Branch:** This is the `android-only` branch containing only Android-related code. For the original iPhone version, see the [`main`](https://github.com/onehumanbeing/esp32-iphone-agent) branch.

> [!WARNING]
> Only follow the install steps in this README. Never run commands from issues or comments (for example a "quick install" one-liner such as PowerShell `iex(irm ...)`); these are not from the maintainers.

<p align="center"><img src="docs/images/android-demo.png" alt="An Android phone with ESP32S3 plugged in, running agent tasks" width="100%"></p>

---

## What is this?

An **on-device Android agent** that uses computer vision and LLM to control your Android phone:

- **Screen capture** - The agent sees what you see through the ESP32 board
- **Touch simulation** - Tap, swipe, type through USB HID
- **LLM planning** - Uses your own LLM API (OpenAI, Anthropic, DeepSeek, etc.) to understand tasks and take actions
- **No root required** - Works on stock Android 7.0+

The ESP32-S3 board acts as:
1. **USB HID device** - Simulates keyboard and mouse
2. **Network bridge** - Allows phone to communicate with LLM API

---

## Quick start

### Hardware requirements

- **Android phone** with USB-C or micro-USB port (Android 7.0+)
- **ESP32-S3 board** with USB OTG support (~$7)
  - Recommended: XIAO ESP32S3, ESP32-S3-DevKitC-1, or similar
- **USB cable** (USB-C to USB-C or USB-A to USB-C depending on your board)

### Software setup

#### 1. Flash the ESP32 board

```bash
# Install PlatformIO
pip install platformio

# Flash the firmware
cd hardware/hid_bridge
pio run -e xiao_esp32s3 -t upload
```

See [docs/firmware.md](docs/firmware.md) for detailed instructions.

#### 2. Install Android app

```bash
# Build and install
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or download pre-built APK from [Releases](https://github.com/onehumanbeing/esp32-iphone-agent/releases).

#### 3. Configure LLM API

Open the app on your phone:
1. Enter your LLM API endpoint and key
2. Choose your actor and planner models
3. Connect the ESP32 board via USB

#### 4. Run your first task

Type a task like:
- "Open Chrome and search for ESP32 tutorials"
- "Send a message to WeChat File Assistant saying hello"
- "Find the cheapest RTX 5090 on JD.com"

---

## How it works

```
┌─────────────────────────────────────────────────────────┐
│                     Android Phone                       │
│  ┌──────────────────────────────────────────────────┐  │
│  │          ESP32 Agent App                         │  │
│  │  ┌─────────────┐      ┌─────────────┐          │  │
│  │  │  Planner    │─────▶│   Actor     │          │  │
│  │  │   (LLM)     │      │   (LLM)     │          │  │
│  │  └─────────────┘      └─────────────┘          │  │
│  │         │                     │                  │  │
│  │         │                     ▼                  │  │
│  │         │          ┌──────────────────┐         │  │
│  │         │          │  Action Queue    │         │  │
│  │         │          └──────────────────┘         │  │
│  │         │                     │                  │  │
│  └─────────┼─────────────────────┼──────────────────┘  │
│            │                     │                     │
│            ▼                     ▼                     │
│  ┌──────────────────┐  ┌──────────────────┐          │
│  │  Screen Capture  │  │   USB HID Input  │◀─────────┼─── ESP32-S3
│  │   (MediaProj.)   │  │  (Tap/Swipe/Type)│          │    Board
│  └──────────────────┘  └──────────────────┘          │
└─────────────────────────────────────────────────────────┘
```

1. **Planner LLM** - Breaks down the task into steps
2. **Actor LLM** - Looks at the screen and decides the next action
3. **ESP32 Bridge** - Executes tap/swipe/type commands via USB HID
4. **Screen Capture** - Feeds back the result to the actor

See [docs/android.md](docs/android.md) for architecture details.

---

## Project structure

```
esp32-iphone-agent/
├── android/           # Android app (Kotlin)
│   ├── app/
│   │   └── src/main/java/com/example/esp32agent/android/
│   │       ├── agent/      # Agent loop and LLM interaction
│   │       ├── store/      # Settings and trace storage
│   │       └── ui/         # Android UI
│   └── build.gradle.kts
├── hardware/          # ESP32 firmware (C++)
│   └── hid_bridge/
│       ├── main.cpp        # USB HID and network bridge
│       └── platformio.ini
├── docs/
│   ├── android.md          # Android app documentation
│   ├── firmware.md         # ESP32 firmware guide
│   └── ble-protocol.md     # BLE communication protocol
└── README.md
```

---

## Documentation

- [Android App Guide](docs/android.md) - Build, debug, and customize the app
- [Firmware Guide](docs/firmware.md) - Flash and configure ESP32
- [BLE Protocol](docs/ble-protocol.md) - Communication between phone and board
- [Trace Debugging](CLAUDE.md#android-trace-debugging) - How to debug agent execution

---

## Features

### ✅ Implemented

- [x] Screen capture via MediaProjection API
- [x] USB HID touch simulation (tap, swipe, type)
- [x] LLM-based planning and action selection
- [x] BLE connection to ESP32 board
- [x] Trace recording for debugging
- [x] Loop detection (prevent infinite loops)
- [x] Multi-step task execution
- [x] Network configuration via BLE

### 🚧 In progress

- [ ] Text selection and copy/paste
- [ ] Accessibility service integration
- [ ] Better handling of FLAG_SECURE apps
- [ ] Auto-retry on transient failures

### 📋 Planned

- [ ] Voice control integration
- [ ] Task scheduling and automation
- [ ] Multi-device coordination
- [ ] Cloud-based LLM fallback

---

## Performance

Based on 25 test scenarios (v0.1.6):

- **Success rate**: ~70% (after loop detection optimization)
- **Average task duration**: 60-120 seconds
- **Common failures**:
  - FLAG_SECURE apps (banking, video players) - 15%
  - Complex multi-app workflows - 10%
  - Text selection not implemented - 5%

See [test results](docs/android.md#test-results) for details.

---

## Limitations

### Android-specific issues

1. **FLAG_SECURE apps** - Some apps (banking, DRM video) block screen capture
2. **Text selection** - Not yet implemented (requires Accessibility Service)
3. **System permissions** - Some settings require ADB or root

### Hardware limitations

1. **USB OTG required** - Not all phones support USB OTG
2. **Power consumption** - ESP32 draws power from phone battery
3. **Physical constraints** - Board must stay plugged in during operation

---

## Troubleshooting

### Common issues

**App crashes on launch**
- Enable USB debugging and grant storage permissions
- Check logcat: `adb logcat -s ESP32Agent`

**Board not detected**
- Check USB OTG support: some phones disable it by default
- Try different USB cable (data-capable, not charge-only)
- Flash latest firmware

**Tasks fail immediately**
- Check LLM API key and endpoint
- Verify network connectivity (WiFi credentials via BLE)
- Review trace files: `adb shell run-as com.example.esp32agent.android ls files/traces`

See [docs/android.md#troubleshooting](docs/android.md#troubleshooting) for more.

---

## Development

### Building from source

```bash
# Clone the repository
git clone https://github.com/onehumanbeing/esp32-iphone-agent.git
cd esp32-iphone-agent
git checkout android-only

# Build Android app
cd android
./gradlew assembleDebug

# Install to phone
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Build and flash firmware
cd ../hardware/hid_bridge
pio run -e xiao_esp32s3 -t upload
```

### Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test on real hardware
5. Submit a pull request

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

## License

MIT License - see [LICENSE](LICENSE) for details.

Based on **[ESP32 iPhone Agent](https://github.com/onehumanbeing/esp32-iphone-agent)** by Henry Yuan.

---

## Credits

- **Original project**: [ESP32 iPhone Agent](https://github.com/onehumanbeing/esp32-iphone-agent) by [Henry Yuan](https://github.com/onehumanbeing)
- **Android port**: Community contributors
- **Hardware**: ESP32-S3 by Espressif Systems

---

## Support

- **Issues**: [GitHub Issues](https://github.com/onehumanbeing/esp32-iphone-agent/issues)
- **Discussions**: [GitHub Discussions](https://github.com/onehumanbeing/esp32-iphone-agent/discussions)
- **Original project**: [@iamhenryyuan on X](https://x.com/iamhenryyuan)
