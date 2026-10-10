#!/usr/bin/env bash
# Builds the TapToSleep LSPosed module without Gradle: aapt2 -> javac -> d8 -> apksigner.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="$here/build"
sdk="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
build_tools="$sdk/build-tools/36.0.0"
platform="$sdk/platforms/android-36/android.jar"
xposed_api="${XPOSED_API_JAR:-}"

if [[ ! -d "$build_tools" || ! -f "$platform" ]]; then
    echo "Android SDK not found (looked in $sdk)" >&2
    exit 1
fi
if [[ -z "$xposed_api" ]]; then
    echo "Set XPOSED_API_JAR to de.robv.android.xposed:api:82 jar" >&2
    exit 1
fi

rm -rf "$out"
mkdir -p "$out"/{stubcls,classes,linked}

echo ">> compiling PowerManager stub"
javac -source 17 -target 17 -nowarn -d "$out/stubcls" "$here/stubs/android/os/PowerManager.java"
cp "$platform" "$out/android-mod.jar"
jar uf "$out/android-mod.jar" -C "$out/stubcls" android/os/PowerManager.class

echo ">> aapt2 compile"
"$build_tools/aapt2" compile --dir "$here/res" -o "$out/res.zip"

echo ">> aapt2 link"
"$build_tools/aapt2" link \
    -o "$out/module-unsigned-apk" \
    -I "$platform" \
    --manifest "$here/AndroidManifest.xml" \
    --version-code 1 \
    --version-name 1.0 \
    --min-sdk-version 30 \
    --target-sdk-version 36 \
    "$out/res.zip"

echo ">> javac"
javac -source 17 -target 17 -nowarn \
    -cp "$out/android-mod.jar:$xposed_api" \
    -d "$out/classes" \
    $(find "$here/src" -name '*.java')

echo ">> d8"
"$build_tools/d8" --release --min-api 30 \
    --lib "$out/android-mod.jar" --lib "$xposed_api" \
    --output "$out" $(find "$out/classes" -name '*.class')

echo ">> packaging"
python3 - "$here" "$out" <<'PY'
import os, sys, zipfile
here, out = sys.argv[1], sys.argv[2]
src = os.path.join(out, "module-unsigned-apk")
dst = os.path.join(out, "TapToSleep-unsigned.apk")
# Android 11+ refuses to install an APK whose resources.arsc is compressed: it must be
# STORED and 4-byte aligned (zipalign below does the aligning).
def stored(name):
    return name == "resources.arsc" or name.endswith(".dex")
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename),
                      zipfile.ZIP_STORED if stored(item.filename) else zipfile.ZIP_DEFLATED)
    zout.writestr("assets/xposed_init",
                  open(os.path.join(here, "assets", "xposed_init"), "rb").read(),
                  zipfile.ZIP_DEFLATED)
    zout.write(os.path.join(out, "classes.dex"), "classes.dex", zipfile.ZIP_STORED)
print("packaged", dst)
PY

echo ">> zipalign"
"$build_tools/zipalign" -f 4 "$out/TapToSleep-unsigned.apk" "$out/TapToSleep-aligned.apk"

echo ">> signing"
if [[ ! -f "$out/release.keystore" ]]; then
    keytool -genkeypair -v -keystore "$out/release.keystore" -alias taptosleep \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=TapToSleep, OU=TapToSleep, O=TapToSleep, C=US" >/dev/null
fi
"$build_tools/apksigner" sign \
    --ks "$out/release.keystore" \
    --ks-key-alias taptosleep \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$here/DoubleTapToSleep.apk" \
    "$out/TapToSleep-aligned.apk"

"$build_tools/apksigner" verify --print-certs "$here/DoubleTapToSleep.apk" | head -3
echo ">> built $here/DoubleTapToSleep.apk"
