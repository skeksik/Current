# 0002: Planned libraries (not added yet)

Status: accepted as direction, nothing added yet

- MVVM: `ViewModel` exposing `StateFlow`, common `LoadState<T>`.
- Koin for DI and ViewModels.
- JetBrains Navigation Compose with `@Serializable` type-safe routes.
- Ktor client + kotlinx.serialization for Jellyfin/Seerr/Stash.
- SQLDelight for cache and download metadata (see ADR 0004 for web).
- Coil 3 for images.
- No backend of our own: the app talks directly to the user's servers.

Each library is added to `gradle/libs.versions.toml` when first needed, after checking compatibility with the current Kotlin/CMP versions.
