CC      ?= cc
CFLAGS  ?= -O2 -Wall -Wextra
PREFIX  ?= /usr/local
TESTS   := tests
JAVA_BUILD := tests/java-build
JAVA_PKG   := org/getpixel/magnifier

C_SRCS = capscreen.c capscreen.h

all: get_pixel

get_pixel: get_pixel.c $(C_SRCS)
	$(CC) $(CFLAGS) -o $@ get_pixel.c capscreen.c

# --- C fake-screencap shim + integration runner ---------------------------------
$(TESTS)/fake_screencap: $(TESTS)/fake_screencap.c
	$(CC) $(CFLAGS) -o $@ $<

$(TESTS)/run_tests: $(TESTS)/test_capscreen.c $(C_SRCS) $(TESTS)/fake_screencap
	$(CC) $(CFLAGS) -o $@ $(TESTS)/test_capscreen.c capscreen.c
	chmod +x $(TESTS)/su

# --- Pure-logic Java tests (no Android SDK / JUnit needed) --------------------
$(JAVA_BUILD)/.stamp: \
		magnifier/app/src/main/java/$(JAVA_PKG)/ColorUtil.java \
		magnifier/app/src/main/java/$(JAVA_PKG)/LensMath.java \
		magnifier/app/src/main/java/$(JAVA_PKG)/FrameBuffer.java \
		magnifier/app/src/main/java/$(JAVA_PKG)/CamMath.java \
		magnifier/app/src/main/java/$(JAVA_PKG)/Palette.java \
		magnifier/app/src/test/java/$(JAVA_PKG)/PureLogicTest.java
	mkdir -p $(JAVA_BUILD)
	javac -d $(JAVA_BUILD) $^
	@touch $@

# --- Unified test target -------------------------------------------------------
test: get_pixel $(TESTS)/run_tests $(JAVA_BUILD)/.stamp
	$(TESTS)/run_tests $(TESTS)
	cd $(JAVA_BUILD) && java org.getpixel.magnifier.PureLogicTest

# --- Android APK (requires ANDROID_HOME with platform-34 & build-tools) --------
ANDROID_HOME ?= $(HOME)/Android/Sdk
JAVA_HOME ?= $(shell dirname $$(dirname $$(readlink -f $$(command -v java))))
GRADLE := ./gradlew --no-daemon

release-apk:
	cd magnifier && ANDROID_HOME=$(ANDROID_HOME) JAVA_HOME=$(JAVA_HOME) $(GRADLE) assembleRelease

debug-apk:
	cd magnifier && ANDROID_HOME=$(ANDROID_HOME) JAVA_HOME=$(JAVA_HOME) $(GRADLE) assembleDebug

apk-test:
	cd magnifier && ANDROID_HOME=$(ANDROID_HOME) JAVA_HOME=$(JAVA_HOME) $(GRADLE) testDebugUnitTest

install: all
	install -d $(DESTDIR)$(PREFIX)/bin
	install -m 0755 get_pixel $(DESTDIR)$(PREFIX)/bin/

clean:
	rm -f get_pixel $(TESTS)/run_tests $(TESTS)/fake_screencap
	rm -rf $(JAVA_BUILD)

.PHONY: all test clean install debug-apk release-apk apk-test