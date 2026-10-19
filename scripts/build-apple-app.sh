#!/usr/bin/env bash
# Builds the SwiftUI app unsigned for iOS simulator, macOS and tvOS simulator, as CI does. macOS host with Xcode only.
# The target's build phase runs :apple:embedAndSignAppleFrameworkForXcode, which links "Shared" for each SDK.
# Building by SDK rather than by destination needs no simulator runtimes, only the SDKs that come with Xcode.
set -euo pipefail
cd "$(dirname "$0")/.."

for sdk in iphonesimulator macosx appletvsimulator; do
  echo "--> $sdk"
  xcodebuild -quiet -project appleApp/Current.xcodeproj -target Current -configuration Debug -sdk "$sdk" \
    SYMROOT="$PWD/appleApp/build" CODE_SIGNING_ALLOWED=NO build
done
