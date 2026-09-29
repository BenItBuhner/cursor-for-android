#!/usr/bin/env bash
set -euo pipefail

# On-device scale benchmark for the debug demo backend. Emulator numbers are not phone numbers: compare fleet ratios,
# then run the same APK and this script against the phone whose performance matters.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="com.cursorforandroid.debug"
ACTIVITY="$PKG/com.cursorforandroid.MainActivity"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
FLEETS="BASE,S50,S200,S500"
RUNS=3
SERIAL=""
OUTPUT="${SCALE_PERF_OUTPUT:-$ROOT/build/scale-perf}"
RECORD=""
BUILD=1
IDLE_SECONDS="${SCALE_PERF_IDLE_SECONDS:-30}"
SCROLL_STEPS="${SCALE_PERF_SCROLL_STEPS:-12}"

usage() {
  cat <<'EOF'
Usage: scripts/scale-perf.sh [options]
  --serial SERIAL       adb device (default: the only connected device)
  --apk PATH            debug APK (default: app/build/outputs/apk/debug/app-debug.apk)
  --fleets LIST         comma-separated BASE,S50,S200,S500
  --runs N              repetitions for scrolling scenarios (default: 3)
  --output DIR          raw dumps and scale-results.log
  --record PATH         record the S200 Big Project scroll to this local .mp4
  --no-build            require --apk to exist instead of assembling debug

Phone example:
  adb devices
  scripts/scale-perf.sh --serial R5CT... --apk app-debug.apk --fleets S50,S200,S500 --record /tmp/s200.mp4

The script clears only com.cursorforandroid.debug, installs the debug APK, enters the in-memory demo, and never contacts
Cursor. Marker files live under that debug app's cache directory and are removed by each pm clear.
EOF
}

while (($#)); do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --fleets) FLEETS="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --record) RECORD="$2"; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || { echo "--runs must be positive" >&2; exit 2; }
mkdir -p "$OUTPUT/raw"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB+=(-s "$SERIAL")

if [[ "$BUILD" == 1 && ! -f "$APK" ]]; then
  (cd "$ROOT" && ./gradlew :app:assembleDebug)
fi
[[ -f "$APK" ]] || { echo "Debug APK not found: $APK" >&2; exit 2; }
"${ADB[@]}" get-state >/dev/null
"${ADB[@]}" install -r -t "$APK" >/dev/null

RESULTS="$OUTPUT/scale-results.log"
TSV="$OUTPUT/scale-results.tsv"
: >"$RESULTS"
printf 'scenario\tfleet\trun\tjank_pct\tp50\tp90\tp95\tp99\tframes\topen_ms\tpss_mb\tgc\tslow_ui\tslow_draw\n' >"$TSV"

ui_xml() {
  "${ADB[@]}" shell uiautomator dump /sdcard/scale-ui.xml >/dev/null 2>&1 || true
  "${ADB[@]}" exec-out cat /sdcard/scale-ui.xml 2>/dev/null || true
}

ui_tap() {
  local attr="$1" needle="$2" xml="$OUTPUT/ui.xml"
  ui_xml >"$xml"
  python3 - "$xml" "$attr" "$needle" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, attr, needle = sys.argv[1:]
try:
    root = ET.parse(path).getroot()
except Exception:
    raise SystemExit(1)
needle = needle.casefold()
matches = []
for node in root.iter("node"):
    value = node.attrib.get(attr, "")
    if needle in value.casefold():
        m = re.fullmatch(r"\[(\d+),(\d+)]\[(\d+),(\d+)]", node.attrib.get("bounds", ""))
        if m:
            matches.append((value.casefold() != needle, value, tuple(map(int, m.groups()))))
if not matches:
    raise SystemExit(1)
_, _, (x1, y1, x2, y2) = sorted(matches)[0]
print((x1 + x2) // 2, (y1 + y2) // 2)
PY
}

tap_node() {
  local xy
  xy="$(ui_tap "$1" "$2")" || return 1
  # shellcheck disable=SC2086
  "${ADB[@]}" shell input tap $xy
}

wait_tap() {
  local attr="$1" value="$2" attempts="${3:-20}"
  local i
  for ((i=0; i<attempts; i++)); do
    if tap_node "$attr" "$value"; then return 0; fi
    sleep 0.5
  done
  return 1
}

screen_size() {
  "${ADB[@]}" shell wm size | awk -F'[:x ]+' '/Physical size|Override size/{w=$(NF-1); h=$NF} END{print w, h}'
}

swipe_up() {
  local w h
  read -r w h < <(screen_size)
  "${ADB[@]}" shell input swipe "$((w/2))" "$((h*4/5))" "$((w/2))" "$((h/5))" 120
}

swipe_down() {
  local w h
  read -r w h < <(screen_size)
  "${ADB[@]}" shell input swipe "$((w/2))" "$((h/5))" "$((w/2))" "$((h*4/5))" 120
}

roundtrip() {
  local cycles="${1:-3}" steps="${2:-$SCROLL_STEPS}" c i
  for ((c=0; c<cycles; c++)); do
    for ((i=0; i<steps; i++)); do swipe_up; done
    for ((i=0; i<steps; i++)); do swipe_down; done
  done
}

open_sidebar() {
  local w h
  read -r w h < <(screen_size)
  "${ADB[@]}" shell input swipe 1 "$((h/2))" "$((w*4/5))" "$((h/2))" 250
  sleep 1
}

close_sidebar() {
  tap_node content-desc "Toggle sidebar" >/dev/null 2>&1 || "${ADB[@]}" shell input keyevent 4
  sleep 1
}

reset_stats() {
  "${ADB[@]}" shell dumpsys gfxinfo "$PKG" reset >/dev/null
  "${ADB[@]}" logcat -c
}

value_or_na() {
  local value="$1"
  [[ -n "$value" ]] && printf '%s' "$value" || printf 'NA'
}

collect_stats() {
  local scenario="$1" fleet="$2" run="$3" open_ms="${4:-NA}"
  local stem="${fleet}-${scenario}-${run}" gfx="$OUTPUT/raw/$stem-gfxinfo.txt" mem="$OUTPUT/raw/$stem-meminfo.txt" logs="$OUTPUT/raw/$stem-logcat.txt"
  "${ADB[@]}" shell dumpsys gfxinfo "$PKG" framestats >"$gfx"
  "${ADB[@]}" shell dumpsys meminfo "$PKG" >"$mem"
  local pid
  pid="$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r')"
  if [[ -n "$pid" ]]; then
    "${ADB[@]}" logcat -d --pid="$pid" >"$logs"
  else
    "${ADB[@]}" logcat -d >"$logs"
  fi

  local frames jank p50 p90 p95 p99 pss_kb pss_mb gc slow_ui slow_draw
  frames="$(awk -F: '/Total frames rendered:/{gsub(/ /,"",$2); print $2; exit}' "$gfx")"
  jank="$(awk -F'[()%]' '/Janky frames:/{gsub(/ /,"",$2); print $2; exit}' "$gfx")"
  p50="$(awk '/50th percentile:/{gsub(/ms/,"",$3); print $3; exit}' "$gfx")"
  p90="$(awk '/90th percentile:/{gsub(/ms/,"",$3); print $3; exit}' "$gfx")"
  p95="$(awk '/95th percentile:/{gsub(/ms/,"",$3); print $3; exit}' "$gfx")"
  p99="$(awk '/99th percentile:/{gsub(/ms/,"",$3); print $3; exit}' "$gfx")"
  slow_ui="$(awk -F: '/Number Slow UI thread:/{gsub(/ /,"",$2); print $2; exit}' "$gfx")"
  slow_draw="$(awk -F: '/Number Slow bitmap uploads:|Number Slow issue draw commands:/{gsub(/ /,"",$2); sum+=$2} END{if(sum!="") print sum}' "$gfx")"
  pss_kb="$(awk '/TOTAL PSS:/{print $3; exit} /^ *TOTAL +[0-9]/{print $2; exit}' "$mem")"
  pss_mb="$(awk -v kb="${pss_kb:-0}" 'BEGIN{printf "%.1f", kb/1024}')"
  gc="$(awk 'BEGIN{IGNORECASE=1} /GC freed|concurrent copying GC|young concurrent copying/{n++} END{print n+0}' "$logs")"

  frames="$(value_or_na "$frames")"; jank="$(value_or_na "$jank")"
  p50="$(value_or_na "$p50")"; p90="$(value_or_na "$p90")"; p95="$(value_or_na "$p95")"; p99="$(value_or_na "$p99")"
  slow_ui="$(value_or_na "$slow_ui")"; slow_draw="$(value_or_na "$slow_draw")"
  local line="SCALE $scenario fleet=$fleet run=$run jank_pct=$jank p50=$p50 p90=$p90 p95=$p95 p99=$p99 frames=$frames open_ms=$open_ms pss_mb=$pss_mb gc=$gc slow_ui=$slow_ui slow_draw=$slow_draw"
  echo "$line" | tee -a "$RESULTS"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$scenario" "$fleet" "$run" "$jank" "$p50" "$p90" "$p95" "$p99" "$frames" "$open_ms" "$pss_mb" "$gc" "$slow_ui" "$slow_draw" >>"$TSV"
}

start_fleet() {
  local fleet="$1" marker="" start
  "${ADB[@]}" shell am force-stop "$PKG"
  "${ADB[@]}" shell pm clear "$PKG" >/dev/null
  if [[ "$fleet" != "BASE" ]]; then
    marker="perf-scale-${fleet#S}"
    "${ADB[@]}" shell run-as "$PKG" mkdir -p cache
    "${ADB[@]}" shell run-as "$PKG" touch "cache/$marker"
  fi
  "${ADB[@]}" logcat -c
  start="$("${ADB[@]}" shell am start -W -n "$ACTIVITY")"
  OPEN_MS="$(awk -F: '/TotalTime:/{gsub(/ /,"",$2); print $2; exit}' <<<"$start")"
  wait_tap text "Try the demo" 30 || true
  sleep 4
}

open_big_project() {
  local id="$1"
  "${ADB[@]}" shell am start -W -a android.intent.action.VIEW -d "https://cursor.com/agents/$id" "$PKG" >/dev/null
  sleep 5
}

open_project_details() {
  wait_tap content-desc "Open panel" 15 || true
  sleep 2
  wait_tap text "Details" 10 || true
  sleep 3
}

close_panel() {
  tap_node content-desc "Close panel" >/dev/null 2>&1 || "${ADB[@]}" shell input keyevent 4
  sleep 2
}

record_s200() {
  [[ -n "$RECORD" ]] || return 0
  local remote="/sdcard/scale-s200-big-project.mp4"
  open_big_project "bc-scale-big-project"
  open_project_details
  "${ADB[@]}" shell rm -f "$remote"
  "${ADB[@]}" shell screenrecord --bit-rate 8000000 --time-limit 20 "$remote" &
  local recorder=$!
  roundtrip 1 8
  wait "$recorder" || true
  mkdir -p "$(dirname "$RECORD")"
  "${ADB[@]}" pull "$remote" "$RECORD" >/dev/null
  echo "Recorded $RECORD"
}

IFS=',' read -r -a FLEET_LIST <<<"$FLEETS"
for fleet in "${FLEET_LIST[@]}"; do
  case "$fleet" in BASE) BIG_ID="bc-demo-0018" ;; S50|S200|S500) BIG_ID="bc-scale-big-project" ;; *) echo "Unsupported fleet: $fleet" >&2; exit 2 ;; esac
  start_fleet "$fleet"

  reset_stats
  sleep "$IDLE_SECONDS"
  collect_stats agent_list_idle "$fleet" 1 "${OPEN_MS:-NA}"

  for ((run=1; run<=RUNS; run++)); do
    open_sidebar
    reset_stats
    roundtrip 3
    collect_stats agent_list_scroll "$fleet" "$run" "${OPEN_MS:-NA}"
    close_sidebar
  done

  open_big_project "$BIG_ID"
  open_project_details
  reset_stats
  sleep "$IDLE_SECONDS"
  collect_stats big_project_idle "$fleet" 1 "${OPEN_MS:-NA}"

  for ((run=1; run<=RUNS; run++)); do
    reset_stats
    roundtrip 3
    close_panel
    roundtrip 3
    collect_stats big_children_transcript_scroll "$fleet" "$run" "${OPEN_MS:-NA}"
    open_project_details
  done

  close_panel
  for ((run=1; run<=RUNS; run++)); do
    reset_stats
    roundtrip 3
    collect_stats long_streaming_transcript_scroll "$fleet" "$run" "${OPEN_MS:-NA}"
  done

  [[ "$fleet" == "S200" ]] && record_s200
done

python3 - "$TSV" "$RESULTS" <<'PY'
import csv, statistics, sys
tsv, output = sys.argv[1:]
with open(tsv, newline="") as f:
    rows = list(csv.DictReader(f, delimiter="\t"))
keys = []
for row in rows:
    key = (row["scenario"], row["fleet"])
    if key not in keys:
        keys.append(key)
fields = ["jank_pct", "p50", "p90", "p95", "p99", "frames", "open_ms", "pss_mb", "gc", "slow_ui", "slow_draw"]
with open(output, "a") as out:
    for scenario, fleet in keys:
        group = [r for r in rows if (r["scenario"], r["fleet"]) == (scenario, fleet)]
        values = {}
        for field in fields:
            nums = [float(r[field]) for r in group if r[field] not in ("", "NA")]
            values[field] = f"{statistics.median(nums):.1f}" if nums else "NA"
        line = " ".join(
            [f"SCALE {scenario}", f"fleet={fleet}", "run=median"] +
            [f"{field}={values[field]}" for field in fields]
        )
        print(line)
        out.write(line + "\n")
PY

echo "Raw dumps: $OUTPUT/raw"
echo "Results:   $RESULTS"
