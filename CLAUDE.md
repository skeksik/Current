# Current

Cross-platform media client. It browses, requests and plays content from **Jellyfin** (library + playback), **Seerr**
(discover + requests) and **Stash** (scenes via GraphQL). Solo project. Later: play/upload from device, download media by link.
Anyone may use it against their own servers: every service is optional, and removable features (adult content first) are extensions (ADR 0008).

Package / app ID: `com.skeksik.current`. Root Gradle project name: `Current`.

## Three UI codebases, one Kotlin core (ADR 0006, ADR 0011)
- **Apple (iOS, macOS, tvOS): SwiftUI**, native look (Liquid Glass on supported OS versions). One SwiftUI codebase shared across the three, with per-platform adaptation.
- **Android, Windows (Linux comes free): Compose Multiplatform.**
- **Web: React + TypeScript (Vite)** in `webApp/` over the core compiled to Kotlin/JS (the `web` library), through an `@JsExport` facade
  (StateFlow -> subscribe/getSnapshot for `useSyncExternalStore`; no exported suspend functions). Desktop and web look nearly the same.
- All logic (data, presentation, playback planning) is shared Kotlin and must stay UI-agnostic.

## Layout
```
androidApp/   Android application shell (MainActivity -> App())
desktopApp/   JVM desktop shell (Compose Desktop; Windows MSI / Linux DEB)
webApp/       browser app: Vite + React + TypeScript over the `web` library (ADR 0011); npm, lockfile committed
iosApp/       Xcode project (SwiftUI shell). macOS and tvOS Xcode targets/projects are still to be created.
shared/       Compose umbrella for Android/desktop: App(), navigation, DI wiring. NO Apple or web targets.
apple/        Kotlin umbrella framework "Shared" for SwiftUI (iOS, macOS, tvOS). NO Compose.
web/          Kotlin/JS umbrella library "current-core" (ES module + .d.ts) for the React app: @JsExport facade, History API router. NO Compose.

composeUi/                 Compose screens, player screen + controls, video surfaces (Android, desktop)
core/model                 domain models                      (pure Kotlin)
core/network               HttpClient, server configuration   (pure Kotlin)
core/database              cache                              (pure Kotlin)
core/playback              PlayerEngine interface, capabilities, playback planner, browser probes (pure Kotlin)
core/designsystem          Compose theme and components       (Compose only)
data/{jellyfin,seerr,stash}                                   (pure Kotlin)
feature/{home,library,requests,player,settings}               presentation only: ViewModel + UI state (pure Kotlin)
extensions/<name>/         (planned, ADR 0008) removable feature groups: own data, presentation, Compose UI and SwiftUI sources
```
Dependencies only point down: `shared` -> `composeUi` -> `feature` -> `data` -> `core`; `apple` exports and `web` wraps `feature`, `data`, `core` (not Compose).
Features never depend on each other. Core never imports an extension; extensions plug in through extension points (ADR 0008).
**Pure-Kotlin modules must not import Compose** (they are consumed by SwiftUI).
Pure-Kotlin modules target android, iOS, macOS (arm64), tvOS, jvm, js (ADR 0011). Compose modules target android and jvm only.
Every module's `build.gradle.kts` copies the shape of an existing one of the same kind (see `core/model` and `composeUi`). New module: `/new-module`.

## Stack (exact versions in gradle/libs.versions.toml)
Kotlin 2.4.x, Compose Multiplatform 1.12.x, AGP 9.x, Gradle wrapper (see gradle/wrapper), Material 3 (Compose side).
Web: React 19, Vite 8, TypeScript 7 (exact versions in webApp/package.json; Node.js 22+).
Added (ADR 0002): Ktor client and kotlinx.serialization (`core:network`), Koin, Navigation Compose (`shared`), Coil 3 (`composeUi`).
Planned, not added yet: SQLDelight, Media3, SKIE for Swift interop.
Research notes with sources and the list of still-unverified claims: docs/platform-notes.md. Do not assert API or platform facts that are not there without checking.

## Commands (Windows: `.\gradlew.bat`, macOS/Linux: `./gradlew`)
```
./gradlew :desktopApp:run                              # run desktop app
npm --prefix webApp run dev                            # run web app (npm ci first; builds the Kotlin library, then Vite)
./gradlew :androidApp:installDebug                     # install on device/emulator
./gradlew jvmTest                                      # JVM tests in every module
npm --prefix webApp run build                          # production web bundle (webApp/build/vite)
npm --prefix webApp run licences                       # licences of shipped npm packages (ADR 0009)
./gradlew :web:jsBrowserDevelopmentLibraryDistribution --continuous  # rebuild the Kotlin library while the dev server runs
./gradlew :apple:linkDebugFrameworkIosSimulatorArm64   # Apple framework (macOS host only; also ...MacosArm64, ...TvosSimulatorArm64)
./scripts/check.sh                                     # everything CI runs on this OS
```
Apple targets can only be built on a Mac. Open `iosApp/iosApp.xcodeproj` in Xcode; its build phase runs `:apple:embedAndSignAppleFrameworkForXcode`.

## Conventions (target conventions for code yet to be written)
- `commonMain` first; platform source sets only for truly platform-specific APIs (small `expect`/`actual`, or an interface implemented per platform).
- Screen = Compose screen in `composeUi` (or SwiftUI view on Apple) + `XViewModel` exposing `StateFlow` in `feature/x`.
- Repositories return domain models; DTOs stay `internal`. The Jellyfin Kotlin SDK is JVM/Android only, so `data:jellyfin` is hand-written on Ktor.
- No hardcoded server URLs, tokens or API keys; never log or commit secrets.
- The repo is public: no hostnames, IPs, keys or private server details in code, tests, fixtures, docs or commits. Describe private
  server forks only generically.
- No feature may assume a service exists: read the capabilities state and hide what is unavailable (ADR 0008).
- Do not catch `CancellationException`.
- Tests with `kotlin.test`; test repositories, planners and ViewModels, not composables.
- Do not add a dependency without asking. If approved, add it to `gradle/libs.versions.toml` only (npm: `webApp/package.json`, exact
  versions, then `npm run licences`). Only permissive or LGPL-2.1 licences;
  never GPL or AGPL in a distributed build (ADR 0009).
- Do not bump versions casually: Kotlin, CMP, AGP and Gradle are coupled. Use `/update-deps`.
- Do not name third-party apps that inspired a design in code, docs or commits.

## Playback (ADR 0007)
The player UI is always the app's own (custom controls). Engines are headless: they play and render to a surface.
`PlaybackPlanner` (shared Kotlin) picks native engine vs fallback engine vs server transcode. The UI layer (SwiftUI or Compose) renders `PlayerUiState` from `feature/player`.
Feature target (automatic route per title, HDR, broad formats, subtitles, PiP/AirPlay, intro skip, playback info overlay): ADR 0007.

## Current status
The module tree and build files exist; sources are empty except `shared` (a placeholder `App()` showing "Current"), the platform shells, the
browser probes and web device profile (`core:playback`), `PlaybackSupportViewModel` (`feature:settings`), the `web` facade and the React app.
Plan (docs/roadmap.md): Windows desktop and web first; the first usable version is Jellyfin browse + play (M1-M2), starting with spikes.

## Git workflow
- `main` is always green and protected; never commit to it directly, never force-push.
- One task = one branch = one PR. Keep PRs small. Never reuse a branch after its PR is merged.
- Branch names are `<type>/<topic>`. Type: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `chore`, or `spike` (an experiment whose PR
  carries only its ADR or notes). Topic: a few lowercase words joined by hyphens, e.g. `feat/jellyfin-sign-in`, `spike/desktop-video`.
- Commit messages: imperative, short subject, optional body explaining why. No `Co-Authored-By`, `Claude-Session` or "Generated with Claude Code" lines in commits or PR descriptions; commits are authored by the repo owner only.
- Author and committer of every commit: `skeksik <personal@skeksik.com>`, whatever the local git config says (in a fresh environment,
  `git config user.name skeksik` and `git config user.email personal@skeksik.com` before the first commit).
- Before a PR run `./scripts/check.sh` and report failures honestly. State what was not verified (e.g. "Apple targets not built on this machine").
- Record architectural decisions as short ADRs in `docs/decisions/`.

## Working with Claude
- Non-trivial work: propose a plan first, then implement in small steps.
- Slash commands: `/new-feature`, `/new-module`, `/check`, `/update-deps`, `/add-playback-engine`. Subagents: `kmp-reviewer`, `api-researcher`.
