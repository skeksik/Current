#!/usr/bin/env bash
# Self-test of check-no-team-id.sh: each way Xcode can write a team ID must fail the check, without printing the value.
set -euo pipefail
cd "$(dirname "$0")/.."

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
value=ZZTESTTEAM

expect() {
  local want=$1 name=$2 file=$3
  local out status=0
  out=$(./scripts/check-no-team-id.sh "$file" 2>&1) || status=$?
  if [ "$want" = fail ] && [ $status -eq 0 ]; then
    echo "check-no-team-id.sh missed: $name" >&2
    exit 1
  fi
  if [ "$want" = pass ] && [ $status -ne 0 ]; then
    echo "check-no-team-id.sh flagged a clean file: $name" >&2
    exit 1
  fi
  if grep -q "$value" <<< "$out"; then
    echo "check-no-team-id.sh printed the team ID: $name" >&2
    exit 1
  fi
}

mkdir -p "$dir/a.xcodeproj" "$dir/b.xcodeproj" "$dir/c.xcodeproj" "$dir/d.xcodeproj"
printf '\t\t\t\tDEVELOPMENT_TEAM = %s;\n' "$value" > "$dir/a.xcodeproj/project.pbxproj"
expect fail "DEVELOPMENT_TEAM build setting" "$dir/a.xcodeproj/project.pbxproj"
printf '\t\t\t\t\tC72D96E367E159DA475156CF = {\n\t\t\t\t\t\tDevelopmentTeam = %s;\n\t\t\t\t\t};\n' "$value" > "$dir/b.xcodeproj/project.pbxproj"
expect fail "DevelopmentTeam target attribute" "$dir/b.xcodeproj/project.pbxproj"
printf '\t\t\t\t"DEVELOPMENT_TEAM[sdk=iphoneos*]" = %s;\n' "$value" > "$dir/c.xcodeproj/project.pbxproj"
expect fail "SDK-conditional DEVELOPMENT_TEAM" "$dir/c.xcodeproj/project.pbxproj"
printf '\t\t\t\tDEVELOPMENT_TEAM = "${TEAM_ID}";\n' > "$dir/d.xcodeproj/project.pbxproj"
expect pass "DEVELOPMENT_TEAM from TEAM_ID" "$dir/d.xcodeproj/project.pbxproj"
printf 'TEAM_ID = %s\n' "$value" > "$dir/e.xcconfig"
expect fail "TEAM_ID in an xcconfig" "$dir/e.xcconfig"

echo "check-no-team-id.sh self-test passed"
