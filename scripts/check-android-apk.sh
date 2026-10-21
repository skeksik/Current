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
# The strings of every source set the Android app packages (commonMain, androidMain), plain and qualified (values-de, ...): each
# compiles to composeResources/<package>/<dir>/<name>.<sourceSet>.cvr.
while IFS= read -r file; do
  module=${file%%/src/*}
  rest=${file#"$module"/src/}
  source_set=${rest%%/*}
  case "$source_set" in
    commonMain | androidMain) ;;
    *)
      echo "Not checked: $file ($source_set is not packaged into the Android app here)" >&2
      continue
      ;;
  esac
  dir=$(basename "$(dirname "$file")")
  package=$(sed -nE 's/^[[:space:]]*packageOfResClass[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$module/build.gradle.kts" | head -n 1)
  if [ -z "$package" ]; then
    echo "$module has Compose resources but its build.gradle.kts sets no packageOfResClass, so this check cannot find them" >&2
    status=1
    continue
  fi
  name=$(basename "$file" .xml)
  compiled="composeResources/$package/$dir/$name.$source_set.cvr"
  # The APK keeps them under a prefix (assets/), so match the whole path after a slash.
  if ! awk -v c="$compiled" '$0 == c || substr($0, length($0) - length(c)) == "/" c { found = 1 } END { exit !found }' <<< "$contents"; then
    echo "The APK lacks the compiled $file ($compiled)" >&2
    status=1
  fi
done < <(find . -path ./build -prune -o -path '*/build' -prune -o -path '*/node_modules' -prune -o \
  -path './*/src/*/composeResources/values*/*.xml' -type f -print | sed 's|^\./||' | sort)
[ $status -eq 0 ] && echo "The APK contains every module's Compose strings."
exit $status
