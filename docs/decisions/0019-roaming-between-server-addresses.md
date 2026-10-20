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
- **Choosing: the best address proved so far, upgraded as higher ones answer.** Every address is asked at once with the service's
  `ServerProbe`, without credentials, and only one that reports the saved server id is used. When no address is in use, the first
  that proves itself is used at once and the check moves up to each higher one that answers, so requests never wait for a higher
  address that hangs until its probe times out (5 s for Jellyfin). An address already in use is only replaced mid-check by a higher
  one; a lower one takes over only when the check ends without it answering. The check ends when the highest proved address has no
  higher one still being asked. An address that answers with another id, a redirect or anything else is never used. In a browser, a
  local `http` address from an `https` page fails as mixed content and simply does not answer.
- **What the check proves:** the id comes from a public endpoint (`/System/Info/Public`), so it stops credentials going to *another*
  server that happens to answer at an address (a different network, a reused IP or name). It does not stop impersonation: anyone who can
  answer on a plain `http` local address can copy the id. HTTPS addresses are protected by TLS as usual.
- **Requests wait for the first proved address** at start and after a network change: a request made while that check runs waits
  until some address proves itself (at most the 1 s settle window plus twice the probe's timeout, 11 s for Jellyfin) instead of going
  to an unproved address, and fails as unreachable if nothing is proved. On a new network the old address is dropped at once, since
  whatever answers there may be another server. For the other triggers the address in use stays until a check finds another.
- **A miss keeps the last proved address.** When nothing answers and the network has not changed, the address in use stays in use and
  the server is marked unreachable; the check is retried with the waits below. Only a network change, or an edit that removes it,
  clears the address.
- **One wait per request:** `ServerClient.request` asks the source once, sends every request in its block to that address, and reports
  that same address when one cannot connect or times out.
- **When it asks again:**
  - at start, and when the user adds, removes or reorders addresses;
  - on a network change (`NetworkChanges`): Android's default network callback; the browser's `online` and `navigator.connection`
    `change` events; on desktop, the network interfaces polled every 5 s (a change in which ones are up or in their addresses); on
    Apple, Network framework's path monitor (`NWPathMonitor`), every update after the first. A network change also starts the retry
    waits over at 15 s. The address is dropped and requests wait from the first update, but the check starts only once updates have
    stopped for 1 s, since one transition comes as a burst;
  - on Android, when the system unblocks the default network (it blocks a background app's network, and a check made then finds
    nothing): the same network is usable again, so the check starts at once, the address in use stays, and the retry waits start over;
  - when a request to the address in use cannot connect or times out (reports for an address no longer in use are ignored), and
    when a request finds no address at all. Never while a check runs, which answers it, and at most once per 15 s after the last
    check ended; a request that comes sooner is kept and the check comes when that window ends. So a caller that retries on every
    failure cannot drive checks back to back, and recovery takes at most 15 s plus a probe;
  - every 5 minutes, asking the address in use and those above it: to move back to a higher (local) address, or to notice that the
    one in use stopped answering, in which case every address is asked;
  - while nothing answers, after 15 s, doubling up to 5 minutes after each miss on the timer (not after one a request or a change
    woke), back to 15 s after a success.
  Edits and resumed networks that arrive during a check make exactly one more check; requests make none. A check whose addresses are edited, or whose network changes,
  while it runs is cancelled at once; a new one starts at once after an edit, and once the network has settled after a change.
- Whether the server answered goes into the capabilities state (`reachable`).
- **The platform refusing network access** (Android's `SecurityException` for a missing `INTERNET` permission, and only that one) is a
  request failure (`Unreachable`), not a bug, so it cannot end the roaming loop. It is logged once, so a shell without the permission
  does not pass for a server that is down. Any other `SecurityException` stays a bug.
- **A service that cannot identify itself without credentials** (Stash, later; an explicit list) gets its addresses in priority order,
  moves to the next on a reported failure and back to the first on a network change. Every saved address is one the user typed or
  confirmed (ADR 0016). Any other service without a probe gets no address.
- **Editing addresses:** `withAddress`, `withoutAddress` (never the last) and `withAddressMoved` on `SavedServer` (`core:network`);
  adding still goes through `ServerSetupViewModel(addTo = …)`, which accepts only the same server id. `ServerAddressesViewModel`
  (`feature:settings`) lists the addresses with their scope and the one in use, and removes and reorders them.

## Consequences
- Android declares `INTERNET` and `ACCESS_NETWORK_STATE`; without the latter there is no signal. Reaching a local server on Android
  still needs a network security config for plain `http` local addresses, and `ACCESS_LOCAL_NETWORK` at target 37
  (docs/platform-notes.md, "Roaming on Android"); those come with the shell.
- On Android a probe can outlast its 5 s timeout when a DNS lookup hangs: over a VPN the owner measured 6-11 s for the internet name
  (docs/platform-notes.md, "Roaming on Android"), so a check can take that long and requests wait the full bound (11 s for Jellyfin)
  before failing. Requests stay bounded by that wait; bounding the probe itself is a follow-up, to be checked on a device.
- A network change does not cancel requests already sent: one sent just before it can go to the old address and hang until its own
  timeout (17.8 s over a VPN on Android).
- Every probe of every address goes into the request log under the server's key, so roaming adds log lines but no hosts.
- The first request after start or a network change can wait up to the settle window plus twice the probe timeout (11 s for
  Jellyfin), longer while a burst of updates goes on. On a Mac (docs/platform-notes.md, "Apple path monitor and roaming on a Mac") a
  transition came as 7-11 path updates within 3-5 s, and opening the Wi-Fi menu alone sent some. Before the settle window each
  update restarted the check: mostly a few milliseconds, but on a phone hotspot, where the home address timed out instead of being
  refused, requests were held 4-7.5 s per update. With the window, a burst costs one check after it ends; an update with nothing
  changed still costs one check. Ignoring Apple updates whose status, interfaces and gateways are unchanged is a possible follow-up.
- A network change no signal reports leaves requests on the old address until one fails or times out, or until the next periodic
  check (up to 5 minutes): if another device answers there on the new network, it receives them, credentials included. Known cases: on
  desktop, a move to another network that keeps the same interface addresses (same subnet and lease); on Apple, whatever the path
  monitor does not report (a move between two Wi-Fi networks is not verified); on the web, any change the browser does not signal
  (Firefox and Safari have only `online`).
- Desktop polling reads the interfaces every 5 s while the app runs. Link-local addresses are ignored, and an interface left with no
  other address does not count, so Docker's `veth*` pairs and idle Hyper-V or WSL adapters do not trigger a check. Each of these still
  does, and makes requests wait for one check: a virtual adapter with a routable address appearing, disappearing or changing (Hyper-V's
  `vEthernet (Default Switch)` or WSL's adapter getting a new private address when the host or WSL restarts, Docker's `docker0` and
  `br-*` bridges, a VPN's tunnel), and an IPv6 temporary address that rotates.
- A server answering at several addresses is asked at all of them on each full check; with a handful of addresses per server that is cheap.
