# 0005: Presentation separate from UI

Status: accepted

## Decision
`feature/*` modules contain only presentation logic (ViewModels, UI state) and no Compose. Compose screens live in `composeUi`.
`feature`, `data` and `core:{model,network,database,playback}` have no UI dependency, so SwiftUI can consume them (ADR 0006).

## Consequences
- Reviewers reject Compose imports in those modules.
- Navigation state: shared Compose navigation on Android/desktop/web; on Apple it is owned by SwiftUI's `NavigationStack`.
