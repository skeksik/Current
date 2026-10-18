# Architecture

> Status: module tree and build files exist; sources are empty. None of the classes named here exist yet.

## Goals
One Kotlin core shared by every platform. Two UI codebases: SwiftUI on Apple (iOS, macOS, tvOS), Compose Multiplatform on Android, Windows/Linux and web (ADR 0006).
The playback UI is always custom; engines are headless (ADR 0007).

## Overview
```
 iosApp / macOS / tvOS (SwiftUI)            androidApp   desktopApp   webApp
            |                                      \          |         /
         apple (framework "Shared")                       shared (App, nav, DI)
            |                                                  |
            |                                              composeUi (Compose screens, player UI, video surfaces)
            |                                                  |
            +------------ feature:* (ViewModels, UI state, PlayerController) ------------+
                                  data:{jellyfin,seerr,stash}
                core:{model,network,database,playback}      core:designsystem (Compose only)
```
Dependencies point down. Features never import each other. Pure-Kotlin modules have no Compose.

## Modules
| Module | Responsibility |
|---|---|
| `core:model` | Domain models (media item, source, request) |
| `core:network` | `HttpClient` factory, server configuration |
| `core:database` | Cache. Web persistence is open (ADR 0004) |
| `core:playback` | `PlayerEngine` interface, `EngineCapabilities`, `PlaybackPlanner`, plan types |
| `core:designsystem` | Compose theme and components (Android/desktop/web) |
| `data:*` | One repository per service. Ktor calls, internal DTOs, mapping to models |
| `feature:*` | ViewModel + UI state per feature; `feature:player` holds `PlayerController` |
| `composeUi` | Compose screens for every feature, custom player controls, per-platform video surface |
| `shared` | Compose umbrella: `App()`, navigation, DI wiring |
| `apple` | Static framework `Shared` exporting core/data/feature to Swift |

## Data flow
UI observes `StateFlow<UiState>` from a ViewModel (Compose collects it directly; SwiftUI through SKIE-generated async sequences, see docs/platform-notes.md)
-> ViewModel calls a repository -> repository calls the service API and maps DTOs to models. A read-through cache comes later.

## Playback
```
PlayerUI (SwiftUI | Compose)  ->  PlayerController (feature:player)  ->  PlaybackPlanner (core:playback)
                                          |                                      |
                                    PlayerEngine (headless, per platform) <-- plan: DirectPlay | DirectStream | Remux | Transcode
```
See ADR 0007.

## Platforms
| Platform | UI | Engine (planned) |
|---|---|---|
| iOS / macOS / tvOS | SwiftUI | AVPlayer; custom fallback engine later |
| Android | Compose | Media3 |
| Windows / Linux | Compose Desktop | libmpv or VLC, undecided (docs/platform-notes.md) |
| Web | Compose Wasm | HTML video (+ hls.js where needed) |
