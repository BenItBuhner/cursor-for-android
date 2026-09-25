#!/usr/bin/env bash
# Installs the toolchain a Cursor Cloud Agent VM needs to build and test this app, and warms the Gradle caches:
# the Android SDK packages CI installs (.github/actions/android-toolchain), JDK 17 (the release toolchain; JDK 21 also
# builds), local.properties, the Gradle wrapper and dependencies, and Robolectric's android-all jars.
#
# Idempotent: every step checks what is already there, so it is the environment's install command and also safe to
# run by hand on a VM that booted without it (`scripts/cloud-agent-setup.sh`, then `source ~/.android-env`).
#
#   --no-warm       skip the Gradle warm-up (SDK and JDK only)
#   --emulator      also install the emulator and an API 35 x86_64 system image, and create the AVD `pixel35`
set -euo pipefail

warm=1
emulator=0
for arg in "$@"; do
  case "$arg" in
    --no-warm) warm=0 ;;
    --emulator) emulator=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/android-sdk}"
# Keep in step with .github/actions/android-toolchain/action.yml.
packages=("platforms;android-36" "platforms;android-35" "build-tools;35.0.0" "platform-tools")
cmdline_tools_zip="commandlinetools-linux-13114758_latest.zip"

log() { printf '[cloud-agent-setup] %s\n' "$*"; }

if [[ ! -x /usr/lib/jvm/java-17-openjdk-amd64/bin/javac ]]; then
  log "installing JDK 17"
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jdk-headless >/dev/null
fi
java_home=/usr/lib/jvm/java-17-openjdk-amd64

sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$sdkmanager" ]]; then
  log "installing Android command-line tools into $sdk"
  tmp="$(mktemp -d)"
  curl -sSfL -o "$tmp/clt.zip" "https://dl.google.com/android/repository/$cmdline_tools_zip"
  unzip -q "$tmp/clt.zip" -d "$tmp"
  mkdir -p "$sdk/cmdline-tools"
  rm -rf "$sdk/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$sdk/cmdline-tools/latest"
  rm -rf "$tmp"
fi

wanted=("${packages[@]}")
if [[ "$emulator" == 1 ]]; then
  wanted+=("emulator" "system-images;android-35;google_apis;x86_64")
fi
missing=()
for p in "${wanted[@]}"; do
  [[ -d "$sdk/${p//;//}" ]] || missing+=("$p")
done
if ((${#missing[@]})); then
  log "installing SDK packages: ${missing[*]}"
  export JAVA_HOME="$java_home"
  yes | "$sdkmanager" --sdk_root="$sdk" --licenses >/dev/null 2>&1 || true
  "$sdkmanager" --sdk_root="$sdk" "${missing[@]}" >/dev/null
fi

if [[ "$emulator" == 1 && ! -d "$HOME/.android/avd/pixel35.avd" ]]; then
  log "creating AVD pixel35"
  echo no | JAVA_HOME="$java_home" "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n pixel35 \
    -k "system-images;android-35;google_apis;x86_64" -d pixel_7 >/dev/null
fi

cat >"$HOME/.android-env" <<EOF
export JAVA_HOME=$java_home
export ANDROID_HOME=$sdk
export ANDROID_SDK_ROOT=$sdk
export PATH=$java_home/bin:$sdk/platform-tools:$sdk/emulator:$sdk/cmdline-tools/latest/bin:\$PATH
EOF
for rc in "$HOME/.bashrc" "$HOME/.profile" "$HOME/.zshrc"; do
  [[ -f "$rc" ]] || continue
  grep -q '\.android-env' "$rc" || printf '\n[ -f "$HOME/.android-env" ] && . "$HOME/.android-env"\n' >>"$rc"
done
# shellcheck disable=SC1091
source "$HOME/.android-env"

echo "sdk.dir=$sdk" >"$repo/local.properties"

# .gitattributes routes screenshots/*.png through this driver: a golden both sides changed keeps this branch's
# version instead of stopping the merge on a binary conflict. Either side's pixels are stale after such a merge, so
# the driver says so; re-record before pushing.
git -C "$repo" config merge.golden.name "keep this branch's golden; re-record after the merge"
git -C "$repo" config merge.golden.driver \
  'echo "golden %P changed on both sides: kept this branch'"'"'s version, re-record screenshots before pushing" >&2'

if [[ "$warm" == 1 ]]; then
  log "warming Gradle (wrapper, dependencies, Robolectric jars, first compile)"
  cd "$repo"
  ./gradlew --quiet :app:compileDebugUnitTestKotlin :app:syncRobolectricSdks
  ./gradlew --stop >/dev/null 2>&1 || true
fi

log "done: JAVA_HOME=$JAVA_HOME ANDROID_HOME=$ANDROID_HOME"
