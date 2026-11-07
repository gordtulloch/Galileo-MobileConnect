#!/bin/sh
# Everything the iOS side must pass, on a Mac with Xcode, Python 3 and Node:
#   contract and tokens in sync and lint-clean; no literal colours in views; core tests on the Mac, including over
#   real TLS against the standalone mock Master; on a simulator, the app builds, the core tests pass (with the
#   real Keychain), the UI tests pass, and Night screenshots contain nothing but red.
#
#   scripts/check_ios.sh                     the whole thing
#   IOS_SIM="iPhone 17 Pro" scripts/check_ios.sh     on another simulator (default iPad Pro 13-inch (M5))
#
# Needs a Python with the packages in tools/mock-master/requirements.txt plus jsonschema and Pillow; set
# GALILEO_PYTHON to it (default .venv/bin/python). With Galileo's dependencies too, and checkouts of Galileo and
# Galileo-Plugins alongside (GALILEO_CHECKOUT, GALILEO_PLUGINS), the client is also tested against the real
# MobileConnect plugin on the real Connect Link server; without them that test is skipped, and says so.
# Everything it starts it also stops.
set -eu
cd "$(dirname "$0")/.."
ROOT=$(pwd)
SIM="${IOS_SIM:-iPad Pro 13-inch (M5)}"
PY="${GALILEO_PYTHON:-.venv/bin/python}"
CHECKOUT="${GALILEO_CHECKOUT:-../Galileo}"
PLUGINS="${GALILEO_PLUGINS:-../Galileo-Plugins}"
OUT=build/ios-check
rm -rf "$OUT"
mkdir -p "$OUT"

pids=""
cleanup() { [ -n "$pids" ] && kill $pids 2>/dev/null || true; }
trap cleanup EXIT

wait_ready() {  # file
    i=0
    while ! grep -q READY "$1" 2>/dev/null; do
        i=$((i + 1))
        if [ "$i" -gt 60 ]; then
            echo "server did not start:" >&2
            cat "$1" >&2
            exit 1
        fi
        sleep 0.5
    done
}

echo "== contract and tokens"
python3 scripts/sync_api.py --check
python3 scripts/gen_tokens.py --check
"$PY" scripts/check_vectors.py
npx --yes @redocly/cli@2.76.0 lint api/openapi.yaml 2>&1 | grep -v "npm notice" || true
npx --yes @redocly/cli@2.76.0 lint api/openapi.yaml >/dev/null 2>&1

echo "== no literal colours in views (ADR-IOS-006)"
if grep -rnE 'Color\.[a-z]+|[^a-zA-Z]Color\(|UIColor|\.foregroundStyle\(\.(primary|secondary|tertiary|white|black|red|gray)|\.foregroundColor\(\.|\.tint\(\.|[^a-zA-Z]Menu[ {(]|[^a-zA-Z]Picker\(|[^a-zA-Z]Slider\(|\.sheet\(|\.alert\(|\.confirmationDialog\(' ios/App \
    | grep -v 'Tokens.generated.swift' | grep -v 'ThemedSlider(' ; then
    echo "literal colours or a system control that can't be held to Night (see above)" >&2
    exit 1
fi

echo "== core tests, with real TLS"
"$PY" tools/mock-master/mock_master.py --host 127.0.0.1 --port 0 --time-scale 20 --no-mdns --print-ready > "$OUT/mock.txt" 2>&1 &
pids="$pids $!"
wait_ready "$OUT/mock.txt"
read -r _ MOCK_PORT MOCK_FP < "$OUT/mock.txt"
GALILEO_MOCK_ADDRESS="127.0.0.1:$MOCK_PORT"
GALILEO_MOCK_FINGERPRINT="$MOCK_FP"
# xcodebuild hands TEST_RUNNER_* to the UI test runner, without the prefix.
TEST_RUNNER_GALILEO_UITEST_ADDRESS="$GALILEO_MOCK_ADDRESS"
export GALILEO_MOCK_ADDRESS GALILEO_MOCK_FINGERPRINT TEST_RUNNER_GALILEO_UITEST_ADDRESS
if [ -d "$CHECKOUT/galileo/connect_link" ] && [ -d "$PLUGINS/mobileconnect/mobileconnect" ] && PYTHONPATH="$CHECKOUT" "$PY" -c 'import peewee, aiohttp, cryptography' 2>/dev/null; then
    PYTHONPATH="$PLUGINS/mobileconnect:$CHECKOUT" "$PY" tools/mock-master/real_link_server.py > "$OUT/real.txt" 2>&1 &
    pids="$pids $!"
    wait_ready "$OUT/real.txt"
    read -r _ REAL_PORT < "$OUT/real.txt"
    GALILEO_REAL_ADDRESS="127.0.0.1:$REAL_PORT"
    export GALILEO_REAL_ADDRESS
else
    echo "(no Galileo and Galileo-Plugins checkouts with their dependencies: the real plugin test is SKIPPED)"
fi
(cd ios/Packages/GalileoCore && swift test 2>&1 | tee "$ROOT/$OUT/core.txt" | grep -E 'error:|✘|skipped|Test run with' || true)
grep -q 'Test run with .* passed' "$OUT/core.txt"
if grep -q 'Client over real TLS.*skipped' "$OUT/core.txt"; then
    echo "the TLS tests were skipped" >&2
    exit 1
fi

echo "== app and tests on $SIM"
# -skipPackagePluginValidation: unattended approval of the OpenAPI generator, which Xcode's UI asks about once.
xcodebuild test -quiet -project ios/GalileoMobileConnect.xcodeproj -scheme GalileoMobileConnect \
    -destination "platform=iOS Simulator,name=$SIM" -derivedDataPath build/ios -resultBundlePath "$OUT/result.xcresult" \
    -skipPackagePluginValidation 2>&1 | grep -vE 'public import|IDETestOperationsObserverDebug|^$|Testing started|@diagnose|#if compiler|#endif|^ *[0-9]+ \||^ *\|' || true
xcrun xcresulttool get test-results summary --path "$OUT/result.xcresult" --compact > "$OUT/summary.json"
python3 - "$OUT/summary.json" <<'P'
import json, sys
d = json.load(open(sys.argv[1]))
print({k: d.get(k) for k in ("result", "totalTestCount", "passedTests", "failedTests", "skippedTests")})
for t in d.get("testFailures", []):
    print("FAIL", t.get("testName"), t.get("failureText", "")[:300])
sys.exit(0 if d.get("result") == "Passed" else 1)
P

echo "== Night contains nothing but red"
xcrun xcresulttool export attachments --path "$OUT/result.xcresult" --output-path "$OUT/shots" >/dev/null
python3 - "$OUT/shots" <<'P'
import json, os, sys
folder = sys.argv[1]
for test in json.load(open(os.path.join(folder, "manifest.json"))):
    for a in test.get("attachments", []):
        name, file = a.get("suggestedHumanReadableName", ""), a.get("exportedFileName")
        if file and name.startswith(("night", "standard")):
            os.replace(os.path.join(folder, file), os.path.join(folder, name.split("_")[0] + ".png"))
P
"$PY" scripts/check_night.py "$OUT/shots"
echo "All checks passed."
