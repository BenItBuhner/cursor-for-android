#!/usr/bin/env bash
# transcript-verify: runs Cursor for Android's own transcript pipeline on this machine against one chat of a real
# account (an API key read from a file you name) or against the recorded fixtures (--replay). Prints, turn by turn,
# what each stage read and decided; read-only unless --send is given.
#
#   tools/transcript-verify/run.sh --agent bc-… --key-file /path/to/key.txt [--mode extended|default] [--turns N]
#                                  [--follow-seconds N] [--show-text] [--out report.txt] [--send "text"]
#   tools/transcript-verify/run.sh --replay
#   tools/transcript-verify/run.sh --help          the full text: every call it makes, and what it never does
#
# It is a Gradle JavaExec on the app's unit-test classpath (see :app:transcriptVerify in app/build.gradle.kts); the
# first run compiles the app and downloads Robolectric's Android image, so expect a few minutes before the report.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Every argument reaches the JVM as one word: --args takes a single string, split on unquoted whitespace.
args=""
for arg in "$@"; do
  quoted="${arg//\\/\\\\}"
  quoted="${quoted//\"/\\\"}"
  args+="\"${quoted}\" "
done

exec "${root}/gradlew" -p "${root}" -q :app:transcriptVerify --args="${args}"
