# the Phigros++ installer, built with the android sdk of shell.nix (direnv). it carries a fallback patch for when
# github can't be reached: by default the one built next to it in ../mod (github.com/ggmolly/PhigrosPlusPlus), with
# `make release-patch` the latest Phigros++ release's, checked against release.pub

LIB ?= ../mod/build/libphigrospp.so
GAMES ?= ../mod/phigrospp/GAMES
# CI signs with the keystore of its secrets, a local build makes its own
export KEYSTORE_PASS ?= phigrospp
TOOLS := $(ANDROID_HOME)/build-tools/35.0.0
JAR := $(ANDROID_HOME)/platforms/android-35/android.jar
APK := out/phigrospp-installer.apk
# zip stores local times
export TZ := UTC

.PHONY: all release-patch install start logs clean

all: $(APK)

release-patch:
	scripts/fetch.sh build/release
	$(MAKE) LIB=build/release/libphigrospp.so GAMES=build/release/GAMES

$(LIB) $(GAMES):
	@echo "$@ is missing: make lib in ../mod, or make release-patch" >&2; exit 1

build/assets/libphigrospp.so: $(LIB)
build/assets/GAMES: $(GAMES)
build/assets/release.pub: release.pub
build/assets/%:
	mkdir -p build/assets && cp $< $@

build/classes.dex: $(wildcard src/*.java)
	rm -rf build/classes && mkdir -p build/classes
	javac -source 8 -target 8 -Xlint:-options -bootclasspath $(JAR):$(TOOLS)/core-lambda-stubs.jar -d build/classes $^
	$(TOOLS)/d8 --release --min-api 24 --lib $(JAR) --output build $$(find build/classes -name '*.class')

build/res.zip: $(shell find res -type f)
	mkdir -p build && $(TOOLS)/aapt2 compile --dir res -o $@

build/unsigned.apk: AndroidManifest.xml build/res.zip build/classes.dex build/assets/libphigrospp.so build/assets/GAMES build/assets/release.pub
	$(TOOLS)/aapt2 link -o $@ --manifest AndroidManifest.xml -I $(JAR) --min-sdk-version 24 --target-sdk-version 35 \
		-A build/assets build/res.zip
	chmod 644 build/classes.dex && touch -t 198001010000 build/classes.dex
	cd build && zip -q -X unsigned.apk classes.dex

keystore.jks:
	keytool -genkeypair -keystore $@ -alias installer -keyalg RSA -keysize 2048 -validity 36500 \
		-storepass $(KEYSTORE_PASS) -keypass $(KEYSTORE_PASS) -dname CN=phigrospp >/dev/null 2>&1

$(APK): build/unsigned.apk keystore.jks
	mkdir -p out
	$(TOOLS)/zipalign -f -p 4 $< build/aligned.apk
	$(TOOLS)/apksigner sign --ks keystore.jks --ks-pass env:KEYSTORE_PASS --ks-key-alias installer \
		--v1-signing-enabled false --v4-signing-enabled false --out $@ build/aligned.apk

install: $(APK)
	adb install -r $(APK)

start:
	adb shell am start -n sh.molly.phigrosplusplus.installer/.MainActivity

logs:
	adb logcat -s 'phigros++'

clean:
	rm -rf build out
