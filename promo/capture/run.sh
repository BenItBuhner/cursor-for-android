#!/usr/bin/env bash
# Films one take of the launch video into promo/capture/out/<test>/.
#
#   promo/capture/run.sh <test> [preview] [frames]
#
#   test     a test of LaunchVideoCapture: hero, desktop, probe, renderCheck
#   preview  keep every Nth frame as a PNG and encode nothing (0, the default, films the take)
#   frames   stop the take after this many frames
#
# Run promo/capture/scripts/prepare.py first whenever scripts/texts.json or scripts/edits.json change.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
test="${1:?usage: run.sh <test> [preview] [frames]}"
preview="${2:-0}"
frames="${3:-}"

# shellcheck disable=SC1090
[ -f ~/.android-env ] && source ~/.android-env

args=(
  -I "$here/promo.init.gradle"
  :app:testDebugUnitTest
  --tests "com.cursorforandroid.promo.LaunchVideoCapture.$test"
  --no-configuration-cache
  # One test JVM and an in-process compiler: the capture VM has room for the daemon and one Robolectric fork.
  -Papp.testForks=1
  -Pkotlin.compiler.execution.strategy=in-process
  "-Dorg.gradle.jvmargs=-Xmx2g -XX:+UseG1GC -XX:MaxMetaspaceSize=768m"
  "-Ppromo.preview=$preview"
)
[ -n "$frames" ] && args+=("-Ppromo.frames=$frames")

cd "$repo"
exec ./gradlew "${args[@]}"
