# 0002: Architecture and libraries

Status: accepted. Added on 2026-10-19 (versions, targets and licences: docs/platform-notes.md, "Libraries per ADR 0002"): Ktor client,
kotlinx.serialization, Koin, Navigation Compose, Coil 3. SQLDelight is not added yet.

- MVVM: `ViewModel` exposing `StateFlow`. (2026-10: there is no common `LoadState<T>`; each ViewModel has its own sealed UI state.)
- Koin for DI. (2026-10: ViewModels are not bound; each page builds its own from Koin-bound interfaces.)
- JetBrains Navigation Compose with `@Serializable` type-safe routes (Android and desktop; the web uses the core's History API router, ADR 0011).
- Ktor client + kotlinx.serialization for Jellyfin/Seerr/Stash.
- SQLDelight for cache and download metadata (see ADR 0004 for web).
- Coil 3 for images (Android and desktop; the web uses plain `<img>`, ADR 0011).
- No backend of our own: the app talks directly to the user's servers.

Each library is added to `gradle/libs.versions.toml` when first needed, after checking compatibility with the current Kotlin/CMP versions.
