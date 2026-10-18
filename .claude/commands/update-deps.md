---
description: Check for newer dependency versions and update gradle/libs.versions.toml safely
argument-hint: [library or "all"]
---

Update dependencies: $ARGUMENTS (default: all)

1. For each entry in `gradle/libs.versions.toml` and the Gradle wrapper, look up the latest stable release (official release notes, via web search).
2. Respect coupling: Compose Multiplatform must support the Kotlin version (JetBrains "Compatibility and versions" page); AGP must match Gradle and the installed Android Studio.
3. Present a table (old -> new, risk) and wait for my approval before editing.
4. Apply, run `./scripts/check.sh` (on a Mac also the Apple framework tasks), report. Revert any bump that breaks the build and say why.
