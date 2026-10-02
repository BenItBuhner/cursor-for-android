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
#   --kvm-only      only make /dev/kvm usable (the environment's start command: the device node is recreated at boot)
set -euo pipefail

warm=1
emulator=0
kvm_only=0
for arg in "$@"; do
  case "$arg" in
    --no-warm) warm=0 ;;
    --emulator) emulator=1 ;;
    --kvm-only) kvm_only=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

log() { printf '[cloud-agent-setup] %s\n' "$*"; }

# The emulator needs /dev/kvm; without it, it runs on software emulation and takes minutes to boot, when it boots.
# The VM has the device (nested virtualisation is on), but owned by a group id no entry in /etc/group names, so the
# agent's user cannot open it. Name that id `kvm`, put the user in it and keep the node 0660 root:kvm; the udev rule
# does the same on a machine that runs udev (this VM does not: its /dev is static, which is why `--kvm-only` is run at
# every boot too). Group membership is read at login, so the shell this runs in does not have it yet: the emulator
# check below, and anything else in the same session, goes through `sg kvm -c`. Says what it did and never fails
# the install: a machine without the device builds and tests everything but the emulator.
kvm_access() {
  if [[ ! -c /dev/kvm ]]; then log "no /dev/kvm: the emulator would run without acceleration"; return 0; fi
  if ! sudo -n true 2>/dev/null; then log "no passwordless sudo: cannot change who may open /dev/kvm"; return 0; fi
  local gid group user
  gid="$(stat -c %g /dev/kvm)"
  group="$(getent group "$gid" | cut -d: -f1 || true)"
  if [[ -z "$group" || "$group" == root ]]; then
    if ! getent group kvm >/dev/null; then
      if [[ "$gid" != 0 ]]; then sudo groupadd -g "$gid" kvm; else sudo groupadd -r kvm; fi
    fi
    group=kvm
  fi
  sudo chgrp "$group" /dev/kvm
  sudo chmod 0660 /dev/kvm
  user="$(id -un)"
  if ! id -nG "$user" | tr ' ' '\n' | grep -qx "$group"; then sudo usermod -aG "$group" "$user"; fi
  if [[ -d /etc/udev/rules.d ]]; then
    printf 'KERNEL=="kvm", GROUP="%s", MODE="0660"\n' "$group" | sudo tee /etc/udev/rules.d/99-kvm-group.rules >/dev/null
  fi
  if sg "$group" -c 'test -r /dev/kvm && test -w /dev/kvm'; then
    log "/dev/kvm is $(stat -c '%A %U:%G' /dev/kvm); $user is in $group (new logins have it; this shell uses sg $group -c)"
  else
    log "/dev/kvm is $(stat -c '%A %U:%G' /dev/kvm) but $user still cannot open it"
    return 1
  fi
}

if [[ "$kvm_only" == 1 ]]; then
  kvm_access || true
  exit 0
fi

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/android-sdk}"
# Keep in step with .github/actions/android-toolchain/action.yml.
packages=("platforms;android-36" "platforms;android-35" "build-tools;35.0.0" "platform-tools")
cmdline_tools_zip="commandlinetools-linux-13114758_latest.zip"

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

kvm_access || true
if [[ "$emulator" == 1 && -c /dev/kvm ]]; then
  # The emulator's own verdict on the acceleration it would get, through the group this shell does not have yet.
  kvm_group="$(stat -c %G /dev/kvm)"
  if accel="$(sg "$kvm_group" -c "'$sdk/emulator/emulator' -accel-check" 2>&1)"; then
    log "emulator acceleration: $(tr '\n' ' ' <<<"$accel")"
  else
    log "emulator acceleration check failed: $(tr '\n' ' ' <<<"$accel")"
  fi
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
