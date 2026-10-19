# 0004: Web persistence (open)

Status: open

## Problem
SQLDelight has no synchronous SQLite driver for Wasm or Kotlin/JS (the web runs on Kotlin/JS since ADR 0011), while Android, iOS and desktop do. `core:database` therefore cannot be
used unchanged on web, and nothing on web may depend on the cache until this is decided.

## Options
1. SQLDelight async API + web worker driver (sql.js / OPFS). Requires `generateAsync` and `suspend` call sites everywhere.
2. Keep SQLDelight on native/desktop/Android and use a simple key-value store (IndexedDB / localStorage) on web behind a common `CacheStore` interface.
3. No offline cache on web: rely on network only.

## Leaning
Option 2 or 3 for M2; revisit when download/offline features (M4) land. Decide before building the read-through cache.
