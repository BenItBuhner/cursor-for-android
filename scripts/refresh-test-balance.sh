#!/usr/bin/env bash
# Rewrites app/test-class-seconds.properties from the per-class times of a green CI run, so the unit-test slices stay
# balanced as the suite grows (`-Papp.testShard`, app/build.gradle.kts). Every CI run uploads its JUnit XML as the
# `unit-tests-results-*` artifacts; this reads them.
#
#   scripts/refresh-test-balance.sh            the newest green `ci.yml` push run on main
#   scripts/refresh-test-balance.sh <run-id>   a specific run
#
# Needs a read-only `gh`. Commit the rewritten file; nothing else changes.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$repo_root/app/test-class-seconds.properties"
export GH_REPO="${GH_REPO:-BenItBuhner/cursor-for-android}"

run="${1:-}"
if [[ -z "$run" ]]; then
  run="$(gh run list --workflow ci.yml --branch main --event push --status success --limit 1 --json databaseId --jq '.[0].databaseId')"
fi
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
gh run download "$run" -D "$tmp" -p 'unit-tests-results-*'

python3 - "$tmp" "$out" "$run" <<'EOF'
import collections, glob, sys, xml.etree.ElementTree as ET

src, out, run = sys.argv[1:]
seconds = collections.Counter()
for path in glob.glob(f"{src}/*/*.xml"):
    suite = ET.parse(path).getroot()
    seconds[suite.get("name").rsplit(".", 1)[-1].split("$")[0]] += float(suite.get("time"))
if not seconds:
    sys.exit(f"run {run} uploaded no JUnit XML")
kept = sorted(((c, round(s)) for c, s in seconds.items() if s >= 2), key=lambda x: (-x[1], x[0]))
with open(out, "w") as f:
    f.write(f"# Seconds each test class takes alone in its JVM, measured in CI run {run}; classes under 2 s count as 1.\n")
    f.write("# Balances the unit-test slices (app/build.gradle.kts). Regenerate with scripts/refresh-test-balance.sh.\n")
    for c, s in kept:
        f.write(f"{c}={s}\n")
print(f"{len(kept)} classes, {sum(seconds.values()):.0f} s in total, from run {run}")
EOF
