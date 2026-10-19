---
description: Split a new Gradle module out of shared (core/data/feature)
argument-hint: <core|data|feature>/<name> [purpose]
---

Create a new module: $ARGUMENTS

1. Read CLAUDE.md and docs/decisions/0003-module-structure.md. Confirm the split is justified by existing code and that dependencies only point down.
2. Copy the build file of an existing module of the same kind: pure Kotlin (`core/model`: android, iOS, macOS, tvOS, jvm, js) or Compose (`composeUi`: android, jvm only). Unique `namespace`; do not invent new Android DSL. Pure-Kotlin modules must not use Compose; if the `apple` framework should expose the module, add it there (api + export); if the web app needs it, add it to `web` (implementation) and run `./gradlew kotlinUpgradeYarnLock` if `kotlinStoreYarnLock` fails.
3. Register it with `include(":layer:name")` in `settings.gradle.kts`, and update CLAUDE.md and docs/architecture.md.
4. Run `./scripts/check.sh` and report.
