# 0019: Roaming between server addresses

Status: accepted (2026-10-20), with several addresses per server.

## Context
A saved server keeps several addresses in the user's priority order (ADR 0016): local ones (`http://192.168.1.10:8096`,
`http://media.lan:8096`) and internet ones (`https://media.example.com`). ADR 0015 made every client read its address from a
`ServerAddressSource` for each request, so roaming needs a smarter source, not new clients. Credentials must reach only an address that
proved to be the same server. docs/platform-notes.md ("Detecting network changes") found no platform that says which network the device
is on, so a signal can only mean "something may have changed".

## Decision
- **One `RoamingServerAddress` per saved server**, made by `RoamingAddressSources` (bound in Koin in place of the first address) and
  shared by every client of that server. It runs in the app scope until the server is removed and follows edits to its addresses.
- **Choosing:** every address is asked at once with the service's `ServerProbe`, without credentials. The first in priority order that
  reports the saved server id is used. An address that answers with another id, a redirect or anything else is never used, so
  credentials go only to a proved address. In a browser, a local `http` address from an `https` page fails as mixed content and simply
  does not answer.
- **When it asks again:**
  - at start, and when the user adds, removes or reorders addresses;
  - on a network change (`NetworkChanges`: Android's default network callback, the browser's `online` and `navigator.connection`
    `change` events; none on desktop and Apple yet);
  - when a request reports the address in use unreachable (reports for an address no longer in use are ignored);
  - while a lower-priority address is in use, every 5 minutes, asking only the addresses above it, to move back to a local address;
  - while nothing answers, after 15 s, doubling up to 5 minutes.
  Requests that arrive during a check are coalesced into one more check.
- **While checking,** the address in use stays in use; the source has no address only when nothing answered, which fails requests as
  unreachable. A removed address is dropped at once. Whether the server answered goes into the capabilities state (`reachable`).
- **A service without a probe** (Stash, later) gets its addresses in priority order, moves to the next on a reported failure and back to
  the first on a network change. Every saved address is one the user typed or confirmed (ADR 0016).
- **Editing addresses:** `withAddress`, `withoutAddress` (never the last) and `withAddressMoved` on `SavedServer` (`core:network`);
  adding still goes through `ServerSetupViewModel(addTo = …)`, which accepts only the same server id. `ServerAddressesViewModel`
  (`feature:settings`) lists the addresses with their scope and the one in use, and removes and reorders them.

## Consequences
- Android declares `ACCESS_NETWORK_STATE`.
- Every probe of every address goes into the request log under the server's key, so roaming adds log lines but no hosts.
- On desktop and Apple the app notices a new network only through a failed request or the periodic check, up to 5 minutes late for a
  move back to local. `NWPathMonitor` on Apple is left for later.
- A server answering at several addresses is asked at all of them on each full check; with a handful of addresses per server that is cheap.
