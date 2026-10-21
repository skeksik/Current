# 0006: SwiftUI for every Apple target

Status: accepted

## Context
Compose draws its own pixels, so it does not adopt Liquid Glass; JetBrains documents native SwiftUI `TabView`/`NavigationStack`/toolbars as the way to get it
(see docs/platform-notes.md for sources). Compose Multiplatform's supported-platform list names Android, iOS, macOS (desktop), Windows, Linux and web; it does not mention tvOS.
Kotlin/Native target tiers: macosArm64 and iOS targets are Tier 1; tvosArm64 and tvosSimulatorArm64 are Tier 2 (compile-tested). macosX64 is deprecated.

## Decision
- One SwiftUI codebase for iOS, macOS and tvOS (platform adaptation where needed).
- Compose Multiplatform for Android, Windows (Linux free) and web (web: superseded by ADR 0011, React + TypeScript). Compose modules have no Apple targets; macOS is not served by the JVM desktop app.
- Kotlin core shared through the `apple` module as a static framework named `Shared`.
- Swift interop for `Flow`/`suspend`/sealed classes: SKIE is the default choice (its latest release at the time of checking, 0.10.15, mentions Kotlin 2.4.20 support);
  Swift export is Alpha. Re-check both when the first Swift screen is written.

## Consequences
- Two UIs to build for each screen; logic must stay in shared Kotlin so behaviour is identical.
- Apple development needs a Mac and Xcode. The iOS, macOS and tvOS apps are one Xcode target, minimum OS 26 (ADR 0012).
- Design tokens (colors, spacing, type scale) should be plain data in a shared module once the design exists, so both UIs read the same values.

## Addendum (2026-10-21): Swift interop for M4
- The first M4 Apple PR is a spike: an Apple facade in `apple` (Koin start, ViewModel holders, `@Throws` where Swift calls can fail;
  docs/reviews/2026-10/apple.md 4.5) with Swift export. It ends in a note on whether Swift export carries one real screen.
- Swift export is Alpha in Kotlin 2.4 and breaking changes are expected (docs/platform-notes.md, "Swift interop", read 2026-10-21).
- SKIE is the fallback if the spike shows Swift export falls short. It is a new dependency, so it needs the owner's approval first.
