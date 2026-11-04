// Copyright (c) 2026 Henry Yuan. MIT; see LICENSE.
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../hid_bridge/src/input/android_touch_report.h"

int main(void) {
  uint8_t report[7];
  ia_touch_encode(report, 1, 16384, 8192);
  const uint8_t down[] = {3, 0, 0, 64, 0, 32, 1};
  assert(memcmp(report, down, sizeof down) == 0);
  ia_touch_encode(report, 0, 16384, 8192);
  assert(report[0] == 0 && report[1] == 0 && report[3] == 64 && report[5] == 32 && report[6] == 0);
  ia_touch_encode(report, 1, -100, 50000);
  assert(report[2] == 0 && report[3] == 0 && report[4] == 255 && report[5] == 127);

  // Decode HID short items: require Digitizers/Touch Screen and a Tip Switch input (BTN_TOUCH),
  // absolute 16-bit X/Y in 0..32767, and exactly 56 input bits to match the encoder.
  unsigned page = 0, usage = 0, size = 0, count = 0, max = 0, input_bits = 0;
  int touchscreen = 0, tip = 0, in_range = 0, contact_id = 0, xy = 0, contact_count = 0, report_id = 0;
  for (size_t i = 0; i < sizeof ia_touch_descriptor;) {
    uint8_t prefix = ia_touch_descriptor[i++];
    unsigned len = prefix & 3;
    if (len == 3) len = 4;
    assert(i + len <= sizeof ia_touch_descriptor);
    unsigned value = 0;
    for (unsigned b = 0; b < len; b++) value |= (unsigned)ia_touch_descriptor[i++] << (8 * b);
    switch (prefix & 0xFC) {
      case 0x04: page = value; break;
      case 0x08:
        usage = value;
        if (page == 0x0D && usage == 4) touchscreen = 1;
        break;
      case 0x24: max = value; break;
      case 0x74: size = value; break;
      case 0x94: count = value; break;
      case 0x84: report_id = value; break;
      case 0x80:
        input_bits += size * count;
        if (page == 0x0D && usage == 0x42 && size == 1 && count == 1 && value == 2) tip = 1;
        if (page == 0x0D && usage == 0x32 && size == 1 && count == 1 && value == 2) in_range = 1;
        if (page == 0x0D && usage == 0x51 && size == 8 && count == 1 && max == 255 && value == 2) contact_id = 1;
        if (page == 0x0D && usage == 0x54 && size == 8 && count == 1 && max == 1 && value == 2) contact_count = 1;
        if (page == 1 && usage == 0x31 && size == 16 && count == 2 && max == 32767 && value == 2) xy = 1;
        break;
    }
  }
  assert(touchscreen && tip && in_range && contact_id && xy && contact_count && report_id == IA_TOUCH_REPORT_ID && input_bits == 56);
  puts("Android touch report tests passed");
  return 0;
}
