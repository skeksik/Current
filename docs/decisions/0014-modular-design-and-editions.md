# 0014: Modular design instead of extensions; two editions

Status: accepted (2026-10-19). Supersedes the extensions of ADR 0008 (its decisions 2, 3 and 5, and the raw payloads of 4) and the
editions of ADR 0009. ADR 0009's licence rules stand.

## Context
ADR 0008 planned removable extensions: feature groups under `extensions/`, plugged in through extension points declared in core, and a
build switch (a Gradle property and a Swift condition) choosing them, with CI building a shell without any. ADR 0009 used the same switch
for three editions (Store, Full, Personal), mainly so a store build could leave out adult content.

The owner decided against it: no extensions, only a modular design that is easy to change, where any component can be replaced. One
public build has every feature; only parts whose licence we cannot pass on are kept out of it.

## Decision
- **No extensions, no extension points, no build switch.** No `extensions/` folder, no `Extension` registry, no CI shell without
  extensions. Every feature is an ordinary module group like the others (`data:*`, `feature:*`, its Compose screens in `composeUi`, its
  SwiftUI views, its React pages).
- **Modules talk through interfaces.** A ViewModel or controller takes what it uses (repositories, engines, the capabilities state) as
  interfaces in its constructor; implementations are `internal` where possible and bound once, in their module's Koin definition.
  Replacing a component is one new implementation and one changed binding, and tests pass fakes the same way. Dependencies still only
  point down (ADR 0003).
- **The shell lists the modules it includes.** Each feature contributes its own destinations to the shell's navigation, so adding or
  removing a feature changes that list and its Koin modules, nothing else.
- **Every service stays optional at runtime** (ADR 0008, decision 1). The capabilities state is `Capabilities` (`core:model`): one
  `ServerStatus` per configured server (signed in, reachable, user, version, features). Features read it through `CapabilitiesState`
  (`core:network`); server setup and sign-in write `MutableCapabilitiesState`. A service is available when one of its servers is signed
  in. Reachability never hides anything: a server that drops off the network keeps its features, which show an offline state, so tabs
  do not come and go with the connection.
- **Unknown content is tolerated, not kept.** Media kinds, services and permissions stay open values (ADR 0013) and JSON is parsed
  leniently. Lists show the kinds the app renders and skip others silently; no raw payload is kept for them.
- **Two editions:**

| Edition | Distribution | Contains |
|---|---|---|
| Store | everything anyone else gets: App Store, Google Play, Microsoft Store and direct downloads (MSI, DEB, APK, web) | every feature, adult content included; only dependencies ADR 0009 allows (permissive, LGPL-2.1) |
| Private | never distributed; built for the owner's devices | Store plus parts whose licence is paid or forbids passing them on: full Dolby Vision processing, GPL-only components |

  The Private edition has no build mechanism yet: none of its parts exists. When the first one does, it selects engine and decoder
  builds (ADR 0010), not features.

## Consequences
- Adult content ships in every Store build and stays hidden until the user connects a server that offers it and has the permission,
  like every other feature. Stores review each app (docs/platform-notes.md: a Stash client is listed in the US App Store, all three
  stores forbid pornographic apps); a rejection is handled when it happens.
- Lists that mix content (search, rows, watchlist) apply the access rules for adult content in shared code (M5), as ordinary feature
  logic rather than registered filters.
- Fewer moving parts than ADR 0008: no registry, no generated code, one build per edition per platform.
- Removing a feature later is still possible, but means editing the shell's module list instead of flipping a switch, and nothing in CI
  proves it stays removable.
