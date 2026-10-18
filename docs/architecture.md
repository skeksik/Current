# Architecture

> Status: module tree and build files exist; sources are empty. None of the classes named here exist yet.

## Goals
One Kotlin core shared by every platform. Two UI codebases: SwiftUI on Apple (iOS, macOS, tvOS), Compose Multiplatform on Android, Windows/Linux and web (ADR 0006).
The playback UI is always custom; engines are headless (ADR 0007).
Every service is optional and removable features are extensions, so the app works for anyone with any subset of servers (ADR 0008).

## Services
| Service | What Current uses it for | Signed in with |
|---|---|---|
| Jellyfin | Libraries, details, playback, watch state (resume, played, favorites) | Access token for a Jellyfin user |
| Seerr | Discover, the wider catalogue (TMDB), requests, watchlist, blocklist, issues | Session of a Seerr user (Jellyfin or local account) |
| Stash | Playback source for an extension's catalogue; watch state stays in Stash | One API key per server (Stash has no users) |

Any non-empty subset can be configured. Features read one **capabilities** state (configured, reachable and signed-in services, server versions
and switches, the user's permissions) and hide what is unavailable.

**Joining catalogue and library.** Seerr records which titles the media server has, including the media server's item id, so a Seerr page can
offer Play from Jellyfin; Jellyfin items carry provider ids (TMDB and others), so a library item can open its Seerr page. Exact fields: docs/platform-notes.md.

## Overview
```
 iosApp / macOS / tvOS (SwiftUI)            androidApp   desktopApp   webApp
            |                                      \          |         /
         apple (framework "Shared")                       shared (App, nav, DI, chosen extensions)
            |                                                  |
            |                                              composeUi (Compose screens, player UI, video surfaces)
            |                                                  |
            +------------ feature:* (ViewModels, UI state, PlayerController) ------------+
                                  data:{jellyfin,seerr,stash}
                core:{model,network,database,playback}      core:designsystem (Compose only)

 extensions/<name>/{data, feature, composeUi} + SwiftUI sources: plug into the layers above through extension points (ADR 0008)
```
Dependencies point down. Features never import each other. Core never imports an extension. Pure-Kotlin modules have no Compose.

## Modules
| Module | Responsibility |
|---|---|
| `core:model` | Domain models: media identity (service + id), open media kinds, artwork with aspect ratio, users, permissions, request status |
| `core:network` | `HttpClient` factory per service, server configuration, error model, log redaction |
| `core:database` | Cache. Web persistence is open (ADR 0004) |
| `core:playback` | `PlayerEngine` interface, `EngineCapabilities`, `PlaybackPlanner`, plan types |
| `core:designsystem` | Compose theme and components (Android/desktop/web) |
| `data:*` | One repository per service. Ktor calls, internal DTOs, mapping to models |
| `feature:*` | ViewModel + UI state per feature; `feature:player` holds `PlayerController` |
| `composeUi` | Compose screens for every feature, custom player controls, per-platform video surface |
| `shared` | Compose umbrella: `App()`, navigation, DI wiring, the extensions this build includes |
| `apple` | Static framework `Shared` exporting core/data/feature (and included extensions) to Swift |
| `extensions/<name>/*` | Planned. Removable features with their own data, presentation and UI modules |

## Extension points (ADR 0008)
Declared in core as pure Kotlin, registered through DI, so both UIs use them:
navigation destinations and tabs, home rows, search sources, renderers per media kind (cards, detail screens), resolvers from a catalogue item
to something playable, settings sections, and content filters. Lists that can mix media kinds apply every content filter before showing an item;
items of a kind no installed renderer handles are dropped.

## Data flow
UI observes `StateFlow<UiState>` from a ViewModel (Compose collects it directly; SwiftUI through SKIE-generated async sequences, see docs/platform-notes.md)
-> ViewModel calls a repository -> repository calls the service API and maps DTOs to models. A read-through cache comes later.

## Playback
```
PlayerUI (SwiftUI | Compose)  ->  PlayerController (feature:player)  ->  PlaybackPlanner (core:playback)
                                          |                                      |
                                    PlayerEngine (headless, per platform) <-- plan: DirectPlay | DirectStream | Remux | Transcode
```
Sources: Jellyfin (the server negotiates routes from a device profile built from `EngineCapabilities`) and, through an extension, Stash
(direct file or Stash's own transcodes). Each source reports progress back to its own server. See ADR 0007.

## Platforms
| Platform | UI | Engine (planned) |
|---|---|---|
| iOS / macOS / tvOS | SwiftUI | AVPlayer; custom fallback engine later |
| Android | Compose | Media3 |
| Windows / Linux | Compose Desktop | Decided by the M1 spike (docs/roadmap.md) |
| Web | Compose Wasm | HTML video (+ hls.js where needed), decided by the M1 spike |

Order of work (docs/roadmap.md): Windows desktop and web first, then Android and Apple, then TV.
