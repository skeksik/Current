#!/usr/bin/env bash
# Starts the packaged desktop app, waits, and fails if it exited or printed an uncaught exception. Needs a display (Xvfb on
# Linux CI). Usage: ./scripts/smoke-desktop-app.sh <launcher> [seconds, default 20]
set -euo pipefail

launcher=${1:?usage: $0 <launcher, e.g. desktopApp/build/compose/binaries/main/app/Current/bin/Current> [seconds]}
seconds=${2:-20}
log=$(mktemp)

"$launcher" > "$log" 2>&1 &
pid=$!
sleep "$seconds"

status=0
if ! kill -0 "$pid" 2> /dev/null; then
  echo "The app exited within $seconds s" >&2
  status=1
else
  kill "$pid" 2> /dev/null || true
fi
if grep -qE 'Exception in thread|NoClassDefFoundError|ClassNotFoundException' "$log"; then
  echo "The app printed an uncaught exception or a missing class" >&2
  status=1
fi
echo "--- app output ---"
cat "$log"
[ $status -eq 0 ] && echo "The app ran for $seconds s"
exit $status
