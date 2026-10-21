#!/usr/bin/env bash
# Fails when the runtime jlink put into the packaged desktop app lacks a JDK module the app needs at run time (the full JDK of
# `:desktopApp:run` hides a missing one). Usage: ./scripts/check-desktop-runtime.sh <createDistributable's app folder>
set -euo pipefail

app=${1:?usage: $0 <app folder, e.g. desktopApp/build/compose/binaries/main/app/Current>}
# Linux: lib/runtime/release; Windows: runtime/release; macOS: Contents/runtime/Contents/Home/release.
release=$(find "$app" -name release -path '*runtime*' -type f | head -n 1)
if [ -z "$release" ]; then
  echo "$app: no runtime/release file; was createDistributable run?" >&2
  exit 1
fi

modules=$(sed -nE 's/^MODULES="(.*)"\r?$/\1/p' "$release")
echo "Runtime modules: $modules"

# java.net.http: ktor-client-java. jdk.accessibility: Java Access Bridge (screen readers on Windows). java.logging: JNA.
# jdk.crypto.ec: elliptic-curve TLS on JDK 21. Keep in step with modules(...) in desktopApp/build.gradle.kts.
status=0
for required in java.base java.desktop java.logging java.net.http jdk.accessibility jdk.crypto.ec jdk.unsupported; do
  if ! grep -qwF -- "$required" <<< "$modules"; then
    echo "$release: missing module $required" >&2
    status=1
  fi
done
exit $status
