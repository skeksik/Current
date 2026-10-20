# 0019: Roaming between server addresses

Status: accepted (2026-10-20), with several addresses per server.

## Context
A saved server keeps several addresses in the user's priority order (ADR 0016): local ones (`http://192.168.1.10:8096`,
`http://media.lan:8096`) and internet ones (`https://media.example.com`). ADR 0015 made every client read its address from a
`ServerAddressSource` for each request, so roaming needs a smarter source, not new clients. Credentials must reach only an address that
proved to be the same server. docs/platform-notes.md ("Detecting network changes") found no platform that says which network the device
is on, so a signal can only mean "something may have changed".

## Decision
- **One `RoamingServerAddress` per saved server**, made by `RoamingAddressSources` (bound in Koin in place of the first address),
  started for every saved server and shared by every client of that server. It runs in the app scope until the server is removed,
  follows edits to its addresses, and has no address once stopped.
- **Choosing:** every address is asked at once with the service's `ServerProbe`, without credentials. The first in priority order that
  reports the saved server id is used; a higher address is waited for until it answers or its probe times out (5 s for Jellyfin). An
  address that answers with another id, a redirect or anything else is never used. In a browser, a local `http` address from an `https`
  page fails as mixed content and simply does not answer.
- **What the check proves:** the id comes from a public endpoint (`/System/Info/Public`), so it stops credentials going to *another*
  server that happens to answer at an address (a different network, a reused IP or name). It does not stop impersonation: anyone who can
  answer on a plain `http` local address can copy the id. HTTPS addresses are protected by TLS as usual.
- **Requests wait for a proved address** at start and after a network change: a request made while that check runs waits for it (at
  most 10 s) instead of going to an unproved address, and fails as unreachable if nothing is proved. On a new network the old address is
  dropped at once, since whatever answers there may be another server. For the other triggers the address in use stays until the check
  finds another.
- **When it asks again:**
  - at start, and when the user adds, removes or reorders addresses;
  - on a network change (`NetworkChanges`: Android's default network callback, the browser's `online` and `navigator.connection`
    `change` events; none on desktop and Apple yet);
  - when a request to the address in use cannot connect or times out (reports for an address no longer in use are ignored);
  - every 5 minutes, asking the address in use and those above it: to move back to a higher (local) address, or to notice that the
    one in use stopped answering, in which case every address is asked;
  - while nothing answers, after 15 s, doubling up to 5 minutes, back to 15 s after a success.
  Triggers that arrive during a check make exactly one more check. A check whose addresses were edited, or whose network changed,
  while it ran is thrown away and asked again.
- Whether the server answered goes into the capabilities state (`reachable`).
- **A service that cannot identify itself without credentials** (Stash, later; an explicit list) gets its addresses in priority order,
  moves to the next on a reported failure and back to the first on a network change. Every saved address is one the user typed or
  confirmed (ADR 0016). Any other service without a probe gets no address.
- **Editing addresses:** `withAddress`, `withoutAddress` (never the last) and `withAddressMoved` on `SavedServer` (`core:network`);
  adding still goes through `ServerSetupViewModel(addTo = …)`, which accepts only the same server id. `ServerAddressesViewModel`
  (`feature:settings`) lists the addresses with their scope and the one in use, and removes and reorders them.

## Consequences
- Android declares `ACCESS_NETWORK_STATE`; without it there is no signal.
- Every probe of every address goes into the request log under the server's key, so roaming adds log lines but no hosts.
- The first request after start or a network change can wait up to the probe timeout.
- **Desktop and Apple have no network signal yet**, so they notice a new network only when a request to the address in use fails or
  times out, or at the next periodic check (up to 5 minutes). Until then requests still go to the old address: if another device answers
  there on the new network, it receives them, credentials included. A signal on those platforms (`NWPathMonitor` on Apple; polling the
  network interfaces on desktop) is the follow-up that closes this.
- A server answering at several addresses is asked at all of them on each full check; with a handful of addresses per server that is cheap.
