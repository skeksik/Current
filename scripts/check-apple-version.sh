#!/usr/bin/env bash
# Fails when the Apple app's MARKETING_VERSION differs from current.version in gradle.properties, the one version source (the other
# platforms read it at build time). Xcode reads the xcconfig itself, so the value is kept there and checked here.
set -euo pipefail
cd "$(dirname "$0")/.."

gradle=$(sed -nE 's/^current\.version=([^[:space:]]+)[[:space:]]*$/\1/p' gradle.properties)
apple=$(sed -nE 's/^MARKETING_VERSION[[:space:]]*=[[:space:]]*([^[:space:]]+)[[:space:]]*$/\1/p' appleApp/Configuration/Config.xcconfig)

if [ -z "$gradle" ] || [ "$gradle" != "$apple" ]; then
  echo "appleApp/Configuration/Config.xcconfig: MARKETING_VERSION is '$apple', gradle.properties current.version is '$gradle'" >&2
  exit 1
fi
