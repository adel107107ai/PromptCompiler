#!/bin/sh
# Everything that must stay true before a commit: the Python suite, prompt parity
# between the Python engine and the generated Java constant, and the packaged APK.
set -eu

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"

echo "== python tests =="
python3 -m unittest discover -s tests -p "test_*.py"

echo "== prompt parity (python -> java) =="
python3 tools/gen_java_prompt.py --check

echo "== android theme coverage =="
python3 tools/check_theme_coverage.py

echo "== android theme application =="
python3 tools/check_theme_apply.py

echo "== android theme attribute usage =="
python3 tools/check_attr_usage.py

echo "== android lifecycle guards =="
python3 tools/check_lifecycle_guard.py

echo "== android view wiring =="
python3 tools/check_double_add.py

echo "== android packaging =="
APK="$REPO/android/build/com.promptengine.app.apk"
if [ -f "$APK" ]; then
    python3 tools/check_apk_alignment.py "$APK"
else
    echo "no APK built yet - run sh tools/build_apk.sh"
fi

echo "== ok =="
