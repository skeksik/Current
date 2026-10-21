#!/usr/bin/env bash
# Runs the same checks as CI. Usage: ./scripts/check.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> No team ID in committed Xcode files"
./scripts/check-no-team-id.sh
./scripts/test-check-no-team-id.sh

echo "==> Apple MARKETING_VERSION matches current.version"
./scripts/check-apple-version.sh

echo "==> Unit tests (JVM, and the web facade's on Node)"
./gradlew jvmTest :web:jsNodeTest

echo "==> Desktop app compiles and packages with the JDK modules it needs (CI also starts it)"
./gradlew :desktopApp:createDistributable
./scripts/check-desktop-runtime.sh desktopApp/build/compose/binaries/main/app/Current*

if ! command -v npm > /dev/null; then
  echo "npm not found: the web app needs Node.js 22 or newer" >&2
  exit 1
fi

echo "==> Web: npm packages and their licences (ADR 0009)"
(cd webApp && npm ci && npm run licences)

echo "==> Web build (Kotlin library, TypeScript, Vite)"
(cd webApp && npm run build)

if [ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then
  echo "==> Android debug build"
  ./gradlew :androidApp:assembleDebug
  ./scripts/check-android-apk.sh
else
  echo "==> Skipping Android build (ANDROID_HOME not set)"
fi

if [ "$(uname)" = "Darwin" ]; then
  echo "==> Apple app (iOS simulator, macOS, tvOS simulator; unsigned)"
  ./scripts/build-apple-app.sh
else
  echo "==> Skipping Apple app (macOS only)"
fi

echo "All checks passed."
