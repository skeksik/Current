# 0020: Cleartext and the local network permission on Android

Status: accepted (2026-10-21). The permission logic and the activity's launcher exist; "Add server" calls it once the Compose shell
has that screen.

## Context
On Android the app could not reach a server on the local network. The owner's runs on Android 17 (target 37) found three gaps; they are
recorded in docs/platform-notes.md by two open PRs, the roaming PR ("Roaming on Android") and the discovery spike ("Local server discovery,
run on devices"), and must merge for those citations to resolve. On this branch, the sections "Local network, discovery and cleartext
HTTP: Android" and "Local network permission on Android: the API" have the documentation behind them:
- the manifest had no `INTERNET`;
- a target of 28 or more blocks plain `http` by default;
- a target of 37 needs the runtime permission `ACCESS_LOCAL_NETWORK` for local addresses. Without it an HTTP request to a local address
  does not fail, it times out, and the system dialog speaks of "nearby devices", not servers.

## Decision
- **Cleartext is allowed for the whole app** (`res/xml/network_security_config.xml`, `base-config cleartextTrafficPermitted="true"`).
  The platform's config is fixed at build time and cannot name addresses the user types, so the app's own rule stays the policy
  (ADR 0016): plain http on a `Local` address is accepted, on an `Internet` address only after a warning the user confirms.
- **The manifest declares `INTERNET` and `ACCESS_LOCAL_NETWORK`.**
- **`LocalNetworkAccess`** (`core:network`): `status()`, `request()` and `openSettings()`, with the states `NotNeeded`, `Granted`,
  `CanAsk` and `Blocked`. `networkModule` binds it through `platformLocalNetworkAccess()`, an expect/actual: `NotNeeded` on desktop,
  web and (for now) Apple; `AndroidLocalNetworkAccess` on Android, which is `NotNeeded` below API 37 (where `INTERNET` is enough and the
  docs say not to ask) and otherwise reads `checkSelfPermission`. No shell module has to override it, so module order does not matter.
- **The dialog** goes through `LocalNetworkRequestBridge`, one app-scoped object that never holds an activity. `MainActivity` registers
  the Activity Result launcher (`RequestPermission`), attaches a launch function in `onCreate` and detaches it in `onDestroy`; its result
  callback hands each answer, with `shouldShowRequestPermissionRationale` after a denial, to the bridge. A request still pending when the
  activity is recreated (rotation) is completed by the new activity's callback. With no activity attached, or when it finishes, a request
  returns at once with no answer and changes nothing.
- **When to ask** (`shouldAskForLocalNetwork`): when the user opens "Add server", or when a saved server has a local address, and only
  while the dialog can still show. Never at start for a user whose servers are all on the internet. The shell explains why in a sentence
  first. A denial means local addresses cannot be reached while internet addresses still work; the shell says so and, once `Blocked`,
  links to the app's settings page and may offer "Try again" (`request()` still asks then, and notices if Android reset its denials).
- **`Blocked`** follows Android's rule that a second denial is permanent. A `false` from the rationale check is believed when an earlier
  denial in this run of denials came with `true`; otherwise (no signal, or a `false` with no earlier `true`, which may be a dialog closed
  without an answer) a counter makes the second denial final. A grant clears everything. The state is kept in the machine-local store
  (ADR 0017), because it describes this device and must not reach a new phone through a backup.

## Consequences
- Any http host is allowed by the platform; a request that skips the app's rule is not stopped by Android.
- Code that probes local addresses (roaming, discovery) should read `status()` first rather than wait for a timeout.
- `100.64.0.0/10` (Tailscale) counts as `Local` (ADR 0016), but the permission excludes VPN connections, so a user whose only local
  address is a Tailscale one is asked for a permission that address presumably does not need. Not verified on a device.
- The machine-local store is not yet kept out of Android backups (ADR 0017, M4); until then a restored phone may carry a `Blocked` state,
  which `status()` corrects as soon as the permission is granted, and "Try again" works around.
- Whether Media3 and Coil honour the cleartext setting is not verified (docs/platform-notes.md).
