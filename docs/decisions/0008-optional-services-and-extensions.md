# 0008: Optional services, removable extensions

Status: accepted (2026-10-18). The mechanism below is a first sketch, validated in M1 before features depend on it.

## Context
Current is not only for the owner's servers: anyone may point it at what they run. That can be Jellyfin alone, Jellyfin with Seerr,
Stash, or modified servers that add content types of their own.
Some features must be removable from a build while the rest keeps working. Adult content is the main example: it must be possible to
ship a build without it (app stores restrict such content; check their current policies before any store release).

## Decision
1. **Every service is optional at runtime.** A profile holds any non-empty subset of Jellyfin, Seerr and Stash. Features read one shared
   capabilities state (which services are configured, reachable and signed in; what the server version supports; what the user's
   permissions allow) and hide whatever is unavailable. No feature assumes a service exists.
2. **Extensions are removable at build time.** A feature that must be removable is an extension: its own module group
   (`extensions/<name>/`: pure-Kotlin data and presentation modules, a Compose UI module, SwiftUI sources). Core modules never import an
   extension. An extension may use core layers (core, data, shared UI components) but not other features.
3. **Extensions plug in through extension points** declared in core (pure Kotlin, so SwiftUI can use them too) and registered through DI:
   navigation destinations and tabs, home rows, search sources, renderers per media kind (cards, detail screens), resolvers that map a
   catalogue item to something playable, settings sections, and content filters (rules that hide items, such as per-user restrictions).
4. **Core tolerates content it does not understand.** Media kinds are open, not a closed enum. Items of an unknown kind (for example from
   a modified server) are kept raw so an extension can decode them; without that extension they are dropped silently. JSON is parsed leniently.
   Lists that can mix kinds (search, watchlist, rows) apply every registered content filter before showing an item.
5. **Shells choose the extensions.** The Compose shells and the Apple framework/Xcode project include extensions through one build switch
   (a Gradle property; a Swift compilation condition). CI builds at least one shell without any extension to prove they stay removable.

## Consequences
- More indirection (interfaces and DI multibinding) than a monolith. Worth it for a removable adult extension, and it gives later optional
  features (downloads, local files) a place to plug in.
- `data:stash` is optional too: only the features that need Stash pull it in, so a build without them does not contain it.
- Kinds being open means SwiftUI sees media kinds as values, not an exhaustive enum; renderers are looked up by kind.
- Navigation is assembled from registered destinations (Compose: each feature contributes to the nav graph; SwiftUI: a registry of views),
  instead of one hand-written graph.
