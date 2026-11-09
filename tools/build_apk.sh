#!/bin/sh
# Gradle-less Android build: aapt2 + javac + d8 + apksigner.
#
# Builds the native app in android/ into android/build/com.promptengine.app.apk.
# Env: ANDROID_JAR, MIN_SDK (26), TARGET_SDK (36), VERSION_CODE, VERSION_NAME,
#      KEYSTORE, KS_ALIAS, KS_PASS.
set -eu

REPO=$(cd "$(dirname "$0")/.." && pwd)
PROJECT=${1:-$REPO/android}
cd "$PROJECT"

MIN_SDK=${MIN_SDK:-26}
TARGET_SDK=${TARGET_SDK:-36}
VERSION_CODE=${VERSION_CODE:-3}
VERSION_NAME=${VERSION_NAME:-1.2.0}
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
KS_ALIAS=${KS_ALIAS:-androiddebugkey}
KS_PASS=${KS_PASS:-android}
export KS_PASS

fail() { echo "build_apk: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "$1 is not installed. Run android_sdk_install first."; }

for tool in aapt2 javac jar d8 apksigner keytool; do need "$tool"; done
[ -f AndroidManifest.xml ] || fail "AndroidManifest.xml not found in $(pwd)"

if [ -z "${ANDROID_JAR:-}" ]; then
    ANDROID_JAR=$(ls -d "$HOME"/android-sdk/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n 1 || true)
fi
[ -n "${ANDROID_JAR:-}" ] && [ -f "$ANDROID_JAR" ] || fail "android.jar not found. Run android_sdk_install, or set ANDROID_JAR."

PACKAGE=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -n 1)
[ -n "$PACKAGE" ] || fail "AndroidManifest.xml has no package attribute"

# The Java prompt constant is generated from the Python policy; refuse to build a stale copy.
python3 "$REPO/tools/gen_java_prompt.py" --check >/dev/null || fail "SystemPrompt.java is out of date - run python3 tools/gen_java_prompt.py"

rm -rf build
mkdir -p build/classes build/dex build/gen

LINK_INPUTS=""
if [ -d res ] && [ -n "$(ls -A res 2>/dev/null)" ]; then
    aapt2 compile --dir res -o build/res.zip
    LINK_INPUTS="build/res.zip"
fi
ASSET_ARGS=""
[ -d assets ] && ASSET_ARGS="-A assets"

# shellcheck disable=SC2086
aapt2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml $ASSET_ARGS \
    --java build/gen --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -o build/base.apk $LINK_INPUTS

CLASSPATH_JARS="$ANDROID_JAR"
LIB_JARS=""
for lib in libs/*.jar; do
    [ -f "$lib" ] || continue
    CLASSPATH_JARS="$CLASSPATH_JARS:$lib"
    LIB_JARS="$LIB_JARS $lib"
done

find src build/gen -name '*.java' > build/sources.txt
[ -s build/sources.txt ] || fail "no .java sources under src/"
javac -source 8 -target 8 -bootclasspath "$CLASSPATH_JARS" -Xlint:-options \
    -d build/classes @build/sources.txt

jar cf build/classes.jar -C build/classes .
# shellcheck disable=SC2086
d8 --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output build/dex build/classes.jar $LIB_JARS

# Appending the dex entries keeps resources.arsc stored. apksigner pads stored
# entries onto 4-byte boundaries itself; check_apk_alignment.py proves it after
# signing, because the SDK's own zipalign is an x86 binary and cannot run here.
cp build/base.apk build/unsigned.apk
for dex in build/dex/classes*.dex; do
    jar --update --file build/unsigned.apk -C build/dex "$(basename "$dex")"
done

if [ ! -f "$KEYSTORE" ]; then
    [ "$KEYSTORE" = "$HOME/.android/debug.keystore" ] || fail "keystore $KEYSTORE does not exist"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

OUT="build/$PACKAGE.apk"
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass env:KS_PASS --key-pass env:KS_PASS --out "$OUT" build/unsigned.apk
apksigner verify "$OUT"
python3 "$REPO/tools/check_apk_alignment.py" "$OUT"

echo "APK: $(pwd)/$OUT"
echo "Package: $PACKAGE"
