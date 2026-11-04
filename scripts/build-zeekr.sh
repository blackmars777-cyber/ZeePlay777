#!/usr/bin/env bash
# Explicit local car-test build; keep credentials outside the source tree.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DIPLAY_AUTH_ASSETS_DIR:?Set DIPLAY_AUTH_ASSETS_DIR to the runtime-assets directory containing offline-mfi/}"
./gradlew -Pdiplay.edition=zeekr :mobile:assembleStandaloneDebug "$@"
python3 scripts/verify_edition_apk.py mobile/build/outputs/apk/debug/mobile-debug.apk \
    --edition zeekr --auth-assets "$DIPLAY_AUTH_ASSETS_DIR"
mkdir -p mobile/build/outputs/zeekr
cp mobile/build/outputs/apk/debug/mobile-debug.apk mobile/build/outputs/zeekr/ZeePlay-standalone-debug.apk
