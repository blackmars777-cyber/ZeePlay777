#!/usr/bin/env bash
# Standalone read-only diagnostics. No DiPlay modules, native libraries or authentication assets.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :vehicleprobe:assembleDebug
python3 scripts/verify_vehicle_probe_apk.py vehicleprobe/build/outputs/apk/debug/vehicleprobe-debug.apk
mkdir -p vehicleprobe/build/outputs/standalone
cp vehicleprobe/build/outputs/apk/debug/vehicleprobe-debug.apk vehicleprobe/build/outputs/standalone/zeekr-vehicle-probe-debug.apk
