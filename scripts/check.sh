#!/usr/bin/env bash
# Runs the same checks as CI. Usage: ./scripts/check.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> Unit tests (JVM)"
./gradlew jvmTest

echo "==> Desktop app compiles"
./gradlew :desktopApp:classes

echo "==> Web build"
./gradlew :webApp:wasmJsBrowserDistribution

if [ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then
  echo "==> Android debug build"
  ./gradlew :androidApp:assembleDebug
else
  echo "==> Skipping Android build (ANDROID_HOME not set)"
fi

if [ "$(uname)" = "Darwin" ]; then
  echo "==> Apple frameworks (iOS, macOS, tvOS)"
  ./gradlew :apple:linkDebugFrameworkIosSimulatorArm64 :apple:linkDebugFrameworkMacosArm64 :apple:linkDebugFrameworkTvosSimulatorArm64
else
  echo "==> Skipping Apple frameworks (macOS only)"
fi

echo "All checks passed."
