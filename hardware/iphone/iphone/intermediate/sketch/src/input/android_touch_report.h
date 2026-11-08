#line 1 "C:\\Users\\CT\\Desktop\\ctbot\\esp32-iphone-agent\\hardware\\hid_bridge\\src\\input\\android_touch_report.h"
// Copyright (c) 2026 Henry Yuan. MIT; see LICENSE.
#pragma once
#include <stdint.h>

// HID Digitizers / Touch Screen / Finger: BTN_TOUCH plus ABS_MT_POSITION_X/Y on
// Linux/Android.  The Contact Identifier is required by hid-multitouch to mark
// the Finger collection as a multitouch report; without it Android can expose
// the interface as an inert generic HID device even though transfers succeed.
#define IA_TOUCH_REPORT_ID 2
#define IA_TOUCH_MAX 32767
static const uint8_t ia_touch_descriptor[] = {
  0x05, 0x0D,       // Usage Page (Digitizers)
  0x09, 0x04,       // Usage (Touch Screen)
  0xA1, 0x01,       // Collection (Application)
  0x85, IA_TOUCH_REPORT_ID,
  0x09, 0x22,       // Usage (Finger)
  0xA1, 0x02,       // Collection (Logical)
  0x09, 0x42,       // Usage (Tip Switch) -> BTN_TOUCH
  0x15, 0x00,       // Logical Minimum (0)
  0x25, 0x01,       // Logical Maximum (1)
  0x75, 0x01,       // Report Size (1)
  0x95, 0x01,       // Report Count (1)
  0x81, 0x02,       // Input (Data, Variable, Absolute)
  0x09, 0x32,       // Usage (In Range); keep the contact valid while pressed
  0x95, 0x01,       // Report Count (1)
  0x81, 0x02,       // Input (Data, Variable, Absolute)
  0x75, 0x06,       // Six padding bits
  0x95, 0x01,
  0x81, 0x03,       // Input (Constant)
  0x09, 0x51,       // Usage (Contact Identifier)
  0x15, 0x00,       // Logical Minimum (0)
  0x26, 0xFF, 0x00, // Logical Maximum (255)
  0x75, 0x08,       // Report Size (8)
  0x95, 0x01,       // Report Count (1)
  0x81, 0x02,       // Input (Data, Variable, Absolute)
  0x05, 0x01,       // Usage Page (Generic Desktop)
  0x09, 0x30,       // Usage (X)
  0x09, 0x31,       // Usage (Y)
  0x15, 0x00,       // Logical Minimum (0)
  0x26, 0xFF, 0x7F, // Logical Maximum (32767)
  0x75, 0x10,       // Report Size (16)
  0x95, 0x02,       // Report Count (2)
  0x81, 0x02,       // Input (Data, Variable, Absolute)
  0xC0,             // End Finger
  0x05, 0x0D,       // Usage Page (Digitizers)
  0x09, 0x54,       // Usage (Contact Count)
  0x15, 0x00,       // Logical Minimum (0)
  0x25, 0x01,       // Logical Maximum (1)
  0x75, 0x08,       // Report Size (8)
  0x95, 0x01,       // Report Count (1)
  0x81, 0x02,       // Input (Data, Variable, Absolute)
  0xC0,             // End Touch Screen
};

static inline uint16_t ia_touch_coordinate(int32_t value) {
  return value < 0 ? 0 : (value > IA_TOUCH_MAX ? IA_TOUCH_MAX : (uint16_t)value);
}

// Explicit little-endian encoding: seven data bytes following the USB report ID.
// The contact identifier stays at zero because this device has one contact slot.
static inline void ia_touch_encode(uint8_t report[7], int down, int32_t x, int32_t y) {
  uint16_t px = ia_touch_coordinate(x), py = ia_touch_coordinate(y);
  report[0] = down ? 0x03 : 0x00; // Tip Switch + In Range
  report[1] = 0;                  // Contact Identifier
  report[2] = (uint8_t)px;
  report[3] = (uint8_t)(px >> 8);
  report[4] = (uint8_t)py;
  report[5] = (uint8_t)(py >> 8);
  report[6] = down ? 1 : 0;       // Contact Count (one active finger)
}
