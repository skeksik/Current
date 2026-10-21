#!/usr/bin/env bash
# Fails when the debug APK lacks a module's Compose strings: the app then crashes at start with MissingResourceException
# (the module's android {} block needs androidResources { enable = true }). Run after ./gradlew :androidApp:assembleDebug.
# Each module's strings are matched under its own resource package (compose.resources { packageOfResClass }), so one module's
# compiled file cannot stand in for another's of the same name.
set -euo pipefail
cd "$(dirname "$0")/.."

apk=androidApp/build/outputs/apk/debug/androidApp-debug.apk
[ -f "$apk" ] || { echo "No $apk: run ./gradlew :androidApp:assembleDebug first" >&2; exit 1; }
contents=$(unzip -Z1 "$apk")

status=0
for file in */src/commonMain/composeResources/values/*.xml */*/src/commonMain/composeResources/values/*.xml; do
  [ -f "$file" ] || continue
  module=${file%%/src/commonMain/*}
  package=$(sed -nE 's/^[[:space:]]*packageOfResClass[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$module/build.gradle.kts" | head -n 1)
  if [ -z "$package" ]; then
    echo "$module has Compose resources but its build.gradle.kts sets no packageOfResClass, so this check cannot find them" >&2
    status=1
    continue
  fi
  name=$(basename "$file" .xml)
  compiled="composeResources/$package/values/$name.commonMain.cvr"
  # The APK keeps them under a prefix (assets/), so match the whole path after a slash.
  if ! awk -v c="$compiled" '$0 == c || substr($0, length($0) - length(c)) == "/" c { found = 1 } END { exit !found }' <<< "$contents"; then
    echo "The APK lacks the compiled $file ($compiled)" >&2
    status=1
  fi
done
[ $status -eq 0 ] && echo "The APK contains every module's Compose strings."
exit $status
