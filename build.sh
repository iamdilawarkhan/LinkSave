#!/bin/bash
# LinkSave build — Gradle's daemon IPC is broken in this sandbox, so we build
# with the SDK tools directly: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Same debug keystore every time, so updates install over the previous build.
set -e

SDK=/home/hatch/workspace/.android-sdk
BT="$SDK/build-tools/34.0.0"
export JAVA_HOME=/home/hatch/workspace/.jdk/jdk-17.0.20.1+1
export PATH="$JAVA_HOME/bin:$PATH"  # d8 invokes `java` from PATH
PROJ=/home/hatch/workspace/linksave-apk
OUT=/tmp/linksave-build
# Keystore lives IN THE PROJECT (not /tmp): /tmp is wiped on service
# restarts, and losing the key means the next APK can't update over the
# previous one. NOTE: the v1.0/v1.1 key (cert 4a953196...) was lost in the
# 2026-11-12 restart; v1.2+ uses the key in this file (cert 58db946f...),
# so v1.2 needs a fresh install (uninstall v1.1 first).
KS="$PROJ/debug.keystore"

rm -rf "$OUT/compiled" "$OUT/classes" "$OUT/dex" "$OUT/apk"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex" "$OUT/apk"

# 1. compile resources (launcher icons, strings). v1.12: the res/xml
# accessibility config is gone with the v1.11 auto-download feature.
# v1.14: adaptive icon — anydpi-v26 XMLs + xxxhdpi layer PNGs + legacy
# round icons join the compile list.
"$BT/aapt2" compile -o "$OUT/compiled" \
    "$PROJ/app/src/main/res/mipmap-"*/ic_launcher.png \
    "$PROJ/app/src/main/res/mipmap-"*/ic_launcher_round.png \
    "$PROJ/app/src/main/res/mipmap-anydpi-v26/"*.xml \
    "$PROJ/app/src/main/res/drawable-xxxhdpi/"*.png \
    "$PROJ/app/src/main/res/xml/"*.xml \
    "$PROJ/app/src/main/res/values/"*.xml

# 2. link manifest + resources -> unsigned APK
"$BT/aapt2" link -o "$OUT/apk/app-unsigned.apk" \
    --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
    -I "$SDK/platforms/android-34/android.jar" \
    "$OUT"/compiled/*.flat

# 3. compile Java (org.json ships inside android.jar)
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$SDK/platforms/android-34/android.jar" \
    -d "$OUT/classes" \
    "$PROJ/app/src/main/java/com/user/linksave/"*.java

# 4. dex (explicit .class files — this d8 rejects a bare directory argument)
rm -rf "$OUT/dex" && mkdir -p "$OUT/dex"
"$BT/d8" --lib "$SDK/platforms/android-34/android.jar" --min-api 24 \
    --output "$OUT/dex" "$OUT"/classes/com/user/linksave/*.class

# 5. add classes.dex to the APK
cd "$OUT/dex" && zip -q "$OUT/apk/app-unsigned.apk" classes.dex && cd - >/dev/null

# 6. align + sign (debug key, standard android credentials)
"$BT/zipalign" -f 4 "$OUT/apk/app-unsigned.apk" "$OUT/apk/app-aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$OUT/apk/LinkSave.apk" "$OUT/apk/app-aligned.apk"

echo "BUILT: $OUT/apk/LinkSave.apk"
"$BT/aapt2" dump badging "$OUT/apk/LinkSave.apk" | grep -E "package:|application-label|sdkVersion|targetSdkVersion"
"$BT/apksigner" verify --verbose "$OUT/apk/LinkSave.apk"
