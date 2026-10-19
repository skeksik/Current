# 0012: One Apple app target, minimum OS 26

Status: accepted (2026-10-19), from closing M0 on a Mac (Xcode 27.1, macOS 26.6.2).

## Context
ADR 0006 puts iOS, macOS and tvOS on one SwiftUI codebase over the `Shared` framework. M0 had an iOS-only Xcode project from the
project wizard (`iosApp/`, minimum iOS 18.2) and needed macOS and tvOS. Xcode 27.1's own templates differ by platform: the multiplatform
App template makes one target for iOS, macOS and visionOS, and the multiplatform Game template adds tvOS as a separate target
(docs/platform-notes.md, "Apple app project").

## Decision
- **One app target** (`Current` in `appleApp/Current.xcodeproj`) with `SUPPORTED_PLATFORMS = iphoneos iphonesimulator macosx appletvos
  appletvsimulator` and one synchronized source folder. Built, launched and checked on all three with Xcode 27.1, and the Kotlin build phase
  picks the right framework slice per SDK. Separate targets would duplicate the build phase and settings with no gain while the code is
  shared. Platform differences go in SDK-conditional build settings and small `#if os(...)` blocks.
- **`iosApp/` renamed to `appleApp/`**, to match the `apple` module and because it now builds three platforms.
- **Minimum OS 26.0 on iOS, macOS and tvOS.** Liquid Glass comes with the 26 releases on all three; Apple's guidance is that on older
  systems an app "maintains its current appearance". Starting at 26 means every Apple screen is designed and tested once, with no fallback
  look and no availability checks around glass. In return, devices that cannot run the 26 releases cannot run the app.
- **Bundle ID `com.skeksik.current`** (CLAUDE.md), without the wizard's `$(TEAM_ID)` suffix. Signing settings stay out of the repo: a
  gitignored `appleApp/Configuration/Local.xcconfig` sets `TEAM_ID` and, for another team, its own bundle ID.

## Consequences
- CI builds the app unsigned for iOS simulator, macOS and tvOS simulator on every PR (`scripts/build-apple-app.sh`). This also catches
  the case where `Shared` is not linked.
- tvOS has no app icon yet: tvOS needs a layered "App Icon & Top Shelf Image" asset, so the tvOS icon name is empty until that artwork
  exists (M8).
- Lowering the minimum later is possible but means adding the fallback look everywhere; decide that before the first real Apple screen.
