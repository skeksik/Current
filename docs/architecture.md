# Architecture

> Status: module tree and build files exist. Filled in so far: `core:model` (ADR 0013), the capabilities state (ADR 0014), server clients (ADR 0015), saved servers and server setup (ADR 0016), secrets and device identity (ADR 0017), Jellyfin sign-in (ADR 0018), the Compose desktop shell (home and settings), the web probes and device profile, the `web`
> facade and the React app. Most classes named here do not exist yet.

## Goals
One Kotlin core shared by every platform. Three UI codebases: SwiftUI on Apple (iOS, macOS, tvOS; ADR 0006), Compose Multiplatform on
Android and Windows/Linux, and React + TypeScript on the web over the core compiled to Kotlin/JS (ADR 0011).
The playback UI is always custom; engines are headless (ADR 0007).
Every service is optional, so the app works for anyone with any subset of servers (ADR 0008). No extensions: the design is modular, and
any component can be replaced by binding another implementation of its interface (ADR 0014).

## Services
| Service | What Current uses it for | Signed in with |
|---|---|---|
| Jellyfin | Libraries, details, playback, watch state (resume, played, favorites) | Access token for a Jellyfin user |
| Seerr | Discover, the wider catalogue (TMDB), requests, watchlist, blocklist, issues | Session of a Seerr user (Jellyfin or local account) |
| Stash | Playback source for the adult catalogue (M5); watch state stays in Stash | One API key per server (Stash has no users) |

Any non-empty subset can be configured. Features read one **capabilities** state and hide what is unavailable: `Capabilities` (`core:model`) holds one
`ServerStatus` per configured server (signed in, reachable, user and permissions, version, reported features), read through
`CapabilitiesState` (`core:network`). A service is available when one of its servers is signed in; an unreachable one keeps its features
with an offline state (ADR 0014).

**Joining catalogue and library.** Seerr records which titles the media server has, including the media server's item id, so a Seerr page can
offer Play from Jellyfin; Jellyfin items carry provider ids (TMDB and others), so a library item can open its Seerr page. Exact fields: docs/platform-notes.md.

## Overview
```
 appleApp (SwiftUI: iOS/macOS/tvOS) androidApp   desktopApp     webApp (React + TypeScript, Vite)
            |                                \         /                |
         apple (framework "Shared")    shared (App, modules, Koin)    web (Kotlin/JS library "current-core",
            |                                    |                    @JsExport facade, History API router)
            |                                    |                      |
            |                                composeUi (screens, Shell + |
            |                                nav, player UI, surfaces)   |
            |                                    |                      |
            +------------ feature:* (ViewModels, UI state, PlayerController) ------------+
                                  data:{jellyfin,seerr,stash}
                core:{model,network,database,playback}      core:designsystem (Compose only)

```
Dependencies point down. Features never import each other. Pure-Kotlin modules have no Compose.

## Modules
| Module | Responsibility |
|---|---|
| `core:model` | Domain models (ADR 0013): media identity (server + id), open values for services, media kinds, image types and permissions, artwork with aspect ratio and blurhash, users, capabilities (ADR 0014). Request status comes with Seerr requests (M3) |
| `core:network` | One client per server that follows the server's current address, credentials added by the data layer, error model (`ServiceException`), redacted request log (ADR 0015); saved servers, address scope and `ServerProbe` (ADR 0016); the capabilities state |
| `core:database` | `KeyValueStore` for settings per platform (ADR 0016: JSON file on desktop, `localStorage`, `SharedPreferences`, `NSUserDefaults`); `SecretStore` for tokens, a machine-local store and `DeviceIdentity` (ADR 0017); later the cache. Web persistence for the cache is open (ADR 0004) |
| `core:playback` | `PlayerEngine` interface, `EngineCapabilities`, `PlaybackPlanner`, plan types, `DeviceProfile`, `VideoRanges` (HDR and Dolby Vision range types for every engine); the browser probes and web device profile (`web` package, runner in `jsMain`) |
| `core:designsystem` | Compose theme from design tokens (`CurrentTokens`), loading, error and empty states, sidebar and page layout, the app's own icons, keyboard focus helpers (Android/desktop) |
| `data:jellyfin` | The server probe (ADR 0016) and `JellyfinAccounts`: sign-in, Quick Connect, sign-out, restored sessions, authorized clients (ADR 0018) |
| `data:*` | One repository per service. Ktor calls, internal DTOs, mapping to models |
| `feature:home` | `HomeViewModel`: where every shell starts; no server, signed out, or signed in |
| `feature:*` | ViewModel + UI state per feature; `feature:player` holds `PlayerController` |
| `composeUi` | Compose screens for every feature, the destination contract and `Shell` (sidebar + pages), custom player controls, per-platform video surface |
| `shared` | Compose umbrella: `App()`, the list of modules it includes, Koin start (`CurrentKoin`, `AppContent`) |
| `apple` | Static framework `Shared` exporting core/data/feature to Swift |
| `web` | Kotlin/JS ES module library `current-core` (with `.d.ts`): an `@JsExport` facade over the ViewModels and the History API router, consumed by the React app in `webApp` (ADR 0011) |

## Modular design (ADR 0014)
No extensions, extension points or build switch. Modules talk through interfaces: a ViewModel or controller takes repositories, engines and
the capabilities state as interfaces in its constructor, and each module binds its implementations once in its Koin definition. Replacing a
component is one new implementation and one changed binding; tests pass fakes the same way. Each feature contributes its own destinations to
the shell's navigation, so the shell's list of modules is the only place that names them all. Lists show the media kinds the app renders and
skip others silently.

**Destinations (Compose).** Features are pure Kotlin, so a feature's Compose pages live in its package in `composeUi` and are described by a
`ShellDestination`: its pages (a `@Serializable` route type and the composable that renders it) and an optional sidebar entry (label, icon,
`order`; the lowest order is where the app starts). Each feature binds its destination in its own Koin module (`homeUiModule`,
`settingsUiModule`, with `shellDestination(...)`, which qualifies each binding by its type so none overrides another); `shared` lists those modules in `App.kt`, and `ShellGraph` collects every binding,
checks that each route is registered once, and orders the sidebar. Pages move through a `Navigator` (open, replace, back). Routes another
feature may open (`SettingsRoute`, `AddServerRoute`, `SignInRoute`) live in the contract's package, so features never import each other. Every page works
with the keyboard alone: focus rings (on focus that arrives by keyboard, gone after a click), Tab and arrow keys move
focus, Enter submits forms, and going back (by any Back) after keyboard input puts focus on what opened the page left, or on the page's first element. The highlighted sidebar entry is the
feature owning the page shown, not the back stack it sits on: Settings pages opened from Home (Add server, Sign in) stay on Home's back
stack, so Back returns to Home, while Settings is highlighted. A sidebar entry always opens its feature's first page. The web shell has its own router (ADR 0011).

Two editions: **Store** (everything distributed, every feature) and **Private** (never distributed; adds parts whose licence is paid or
forbids passing them on, such as full Dolby Vision processing).

## Data flow
UI observes `StateFlow<UiState>` from a ViewModel (Compose collects it directly; SwiftUI through SKIE-generated async sequences, see
docs/platform-notes.md; React through the facade's subscribe/getSnapshot with `useSyncExternalStore`, ADR 0011)
-> ViewModel calls a repository -> repository calls the service API and maps DTOs to models. A read-through cache comes later.

## Playback
```
PlayerUI (SwiftUI | Compose | React)  ->  PlayerController (feature:player)  ->  PlaybackPlanner (core:playback)
                                          |                                      |
                                    PlayerEngine (headless, per platform) <-- plan: DirectPlay | DirectStream | Remux | Transcode
```
Sources: Jellyfin (the server negotiates routes from a device profile built from `EngineCapabilities`) and Stash
(direct file or Stash's own transcodes). Each source reports progress back to its own server. See ADR 0007.

## Platforms
| Platform | UI | Engine (planned) |
|---|---|---|
| iOS / macOS / tvOS | SwiftUI, one Xcode target, minimum OS 26 (ADR 0012) | AVPlayer; custom fallback engine later |
| Android | Compose | Media3 |
| Windows / Linux | Compose Desktop | libmpv in a child window, our controls in a transparent window (ADR 0010) |
| Web | React + TypeScript over Kotlin/JS (ADR 0011) | HTML video + hls.js, device profile from browser probes (ADR 0011) |

Order of work (docs/roadmap.md): Windows desktop and web first, then Android and Apple, then TV.
