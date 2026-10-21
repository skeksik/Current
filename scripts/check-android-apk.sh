#!/usr/bin/env bash
# Fails when the debug APK lacks a module's Compose strings: the app then crashes at start with MissingResourceException
# (the module's android {} block needs androidResources { enable = true }). Run after ./gradlew :androidApp:assembleDebug.
set -euo pipefail
cd "$(dirname "$0")/.."

apk=androidApp/build/outputs/apk/debug/androidApp-debug.apk
[ -f "$apk" ] || { echo "No $apk: run ./gradlew :androidApp:assembleDebug first" >&2; exit 1; }
contents=$(unzip -Z1 "$apk")

status=0
for file in */src/commonMain/composeResources/values/*.xml */*/src/commonMain/composeResources/values/*.xml; do
  [ -f "$file" ] || continue
  name=$(basename "$file" .xml)
  if ! grep -Eq "composeResources/[^/]+/values/$name\.commonMain\.cvr$" <<< "$contents"; then
    echo "The APK lacks the compiled $file (values/$name.commonMain.cvr)" >&2
    status=1
  fi
done
[ $status -eq 0 ] && echo "The APK contains every module's Compose strings."
exit $status
