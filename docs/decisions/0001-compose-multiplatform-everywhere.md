# 0001: Compose Multiplatform for UI (partly superseded by 0006)

Status: superseded for Apple platforms by ADR 0006; still valid for Android, Windows/Linux and web

## Context
Targets are Android, iOS, macOS, Windows and web, with TV later. A solo developer cannot maintain several native UIs.

## Decision
Kotlin + Compose Multiplatform for the UI of Android, desktop (Windows, Linux via JVM) and web (Kotlin/Wasm only, no Kotlin/JS, WasmGC browsers).
Apple platforms moved to SwiftUI in ADR 0006 to get native look and Liquid Glass. Shared logic stays Kotlin.
