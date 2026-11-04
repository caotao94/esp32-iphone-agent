# Host unit tests for the firmware's pure-C parts. Usage: make -C hardware/tests
SRC = ../hid_bridge/src
CFLAGS = -std=c11 -Wall -Wextra -g -fsanitize=address,undefined

all: test test_android_touch

test: test_main.c $(SRC)/bplist/bplist.c $(SRC)/video/gop.c
	$(CC) $(CFLAGS) -o $@ $^
	./test

test_android_touch: test_android_touch.c $(SRC)/input/android_touch_report.h
	$(CC) $(CFLAGS) -o $@ test_android_touch.c
	./test_android_touch

clean:
	rm -f test test_android_touch

.PHONY: all clean
