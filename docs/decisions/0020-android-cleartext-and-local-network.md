# 0020: Cleartext and the local network permission on Android

Status: accepted (2026-10-21). The permission logic exists; the "Add server" screen calls it once the Compose shell has that screen.

## Context
On Android the app could not reach a server on the local network (docs/platform-notes.md, "Roaming on Android" and "Local server
discovery, run on devices"): the manifest had no `INTERNET`, a target of 28 or more blocks plain `http` by default, and a target of 37
needs the runtime permission `ACCESS_LOCAL_NETWORK` for any local address. Without that permission an HTTP request to a local address
does not fail, it times out. The system dialog speaks of "nearby devices", not servers.

## Decision
- **Cleartext is allowed for the whole app** (`res/xml/network_security_config.xml`, `base-config cleartextTrafficPermitted="true"`).
  The platform's config is fixed at build time and cannot name addresses the user types, so the app's own rule stays the policy
  (ADR 0016): plain http on a `Local` address is accepted, on an `Internet` address only after a warning the user confirms.
- **The manifest declares `INTERNET` and `ACCESS_LOCAL_NETWORK`.**
- **`LocalNetworkAccess`** (`core:network`): `status()`, `request()` and `openSettings()`, with the states `NotNeeded`, `Granted`,
  `CanAsk` and `Blocked`. Desktop, web and (for now) Apple bind `NotNeeded`. Android binds `AndroidLocalNetworkAccess`: `NotNeeded`
  below API 37, where `INTERNET` is enough and the docs say not to ask; otherwise the platform's check. The dialog goes through a
  `PermissionRequester` the shell implements with the Activity Result API, so the component needs no activity and no Compose.
- **When to ask** (`shouldAskForLocalNetwork`): when the user opens "Add server", or when a saved server has a local address, and only
  while the dialog can still show. Never at start for a user whose servers are all on the internet. The shell explains why in a sentence
  first. A denial means local addresses cannot be reached while internet addresses still work; the shell says so and, once `Blocked`,
  links to the app's settings page.
- **`Blocked`** is the platform's signal when the shell has one (`shouldShowRequestPermissionRationale` after a denial), believed from the
  second denial on, since Android makes a denial permanent only then; otherwise a counter makes the second denial final. Kept in the
  settings store, because without an activity Android only reports granted or not. A grant clears it.

## Consequences
- Any http host is allowed by the platform; a request that skips the app's rule is not stopped by Android.
- Code that probes local addresses (roaming, discovery) should read `status()` first rather than wait for a timeout.
- Whether Media3 and Coil honour the cleartext setting is not verified (docs/platform-notes.md).
