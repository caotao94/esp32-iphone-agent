// Copyright (c) 2026 Henry Yuan. MIT; see LICENSE.
#pragma once
#include "USBHID.h"
#include "src/input/android_touch_report.h"

// Single-touch digitizer: Android receives finger down/move/up, not absolute mouse reports.
class AndroidTouch : public USBHIDDevice {
  USBHID hid_;
  int16_t x_ = 0, y_ = 0;
  bool down_ = false, commandOk_ = true;
  volatile uint32_t sent_ = 0, failed_ = 0;

  void send() {
    uint8_t report[7];
    ia_touch_encode(report, down_, x_, y_);
    if (hid_.SendReport(IA_TOUCH_REPORT_ID, report, sizeof report)) {
      sent_++;
    } else {
      failed_++;
      commandOk_ = false;
    }
  }

public:
  AndroidTouch() { hid_.addDevice(this, sizeof ia_touch_descriptor); }
  void begin() { hid_.begin(); }
  uint16_t _onGetDescriptor(uint8_t *dst) override {
    memcpy(dst, ia_touch_descriptor, sizeof ia_touch_descriptor);
    return sizeof ia_touch_descriptor;
  }
  void startCommand() { commandOk_ = true; }
  bool commandOk() const { return commandOk_; }
  uint32_t sent() const { return sent_; }
  uint32_t failed() const { return failed_; }

  void move(int16_t x, int16_t y) {
    x_ = ia_touch_coordinate(x);
    y_ = ia_touch_coordinate(y);
    send();
  }
  void press(uint8_t) { down_ = true; send(); }
  void release(uint8_t) { down_ = false; send(); }
};
