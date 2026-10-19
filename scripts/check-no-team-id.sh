#!/usr/bin/env bash
# Fails when a team ID is committed: the repo is public (CLAUDE.md). Xcode writes the team chosen under Signing & Capabilities
# straight into project.pbxproj; it belongs in the gitignored Local.xcconfig next to Config.xcconfig instead (ADR 0012).
# Prints file and line only, never the value.
set -euo pipefail
cd "$(dirname "$0")/.."

status=0
while IFS= read -r file; do
  # Every DEVELOPMENT_TEAM (also SDK-conditional ones) must be the TEAM_ID variable.
  lines=$(grep -nE 'DEVELOPMENT_TEAM[^=]*=' "$file" | grep -vE '=[[:space:]]*"?\$[{(]TEAM_ID[})]"?;' | cut -d: -f1 || true)
  for line in $lines; do
    echo "$file:$line: DEVELOPMENT_TEAM must be \"\${TEAM_ID}\"; set TEAM_ID in Local.xcconfig" >&2
    status=1
  done
done < <(git ls-files '*.xcodeproj/project.pbxproj')

while IFS= read -r file; do
  lines=$(grep -nE '^[[:space:]]*TEAM_ID[[:space:]]*=[[:space:]]*[^[:space:]]' "$file" | cut -d: -f1 || true)
  for line in $lines; do
    echo "$file:$line: TEAM_ID must stay empty in committed files; set it in Local.xcconfig" >&2
    status=1
  done
done < <(git ls-files '*.xcconfig')

exit $status
