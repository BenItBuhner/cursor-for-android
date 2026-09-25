#!/usr/bin/env bash
#
# Cuts a release from a commit CI has already proved. It checks that a green CI run exists for that exact tree (the
# commit's own run, or a pull-request head whose tree is byte-identical), builds and signs the release APK + AAB
# from that commit with the key named in the environment, checks the signer against the pinned certificate, writes
# the release body and publishes the GitHub Release. It never runs lint or the unit tests itself: CI did, on this
# tree, and the run it points at is the evidence. A tree CI has not proved is refused.
#
# Usage:
#   scripts/release-cut.sh --version X.Y.Z[-rc.N] [--sha <commit>] [--notes-file notes.md] [options]
#
#   --version X.Y.Z    the version to release; the tag is vX.Y.Z (a pre-release such as 0.4.0-rc.1 is marked as one)
#   --sha <commit>     the commit to release (default: the tip of origin/main); it must be on origin/main
#   --notes-file FILE  Markdown release notes, placed under the header this script writes
#   --generate-notes   also append GitHub's generated notes (the pull requests since the previous tag)
#   --mode local       build, sign and publish from this machine (default; the way releases are cut today)
#   --mode workflow    push the tag and let release.yml build, sign and publish with the repository secrets
#   --dry-run          build and verify only: no tag, no release; the artifacts stay in --out
#   --no-ci-check      skip the CI evidence (for a tree you have just run lint and the unit tests on yourself)
#   --ci-timeout MIN   how long to wait for an in-progress CI run on the commit (default 20)
#   --out DIR          where the artifacts and the release body go (default: build/release/vX.Y.Z)
#   --keep-worktree    leave the build worktree in place for inspection
#
# Environment (nothing here is ever written into the repository):
#   RELEASE_KEYSTORE_FILE       path to the release keystore (JKS or PKCS12)      required in local mode
#   RELEASE_KEYSTORE_PASSWORD   its password                                      required with the keystore
#   RELEASE_KEY_ALIAS           the key's alias                                   required with the keystore
#   RELEASE_KEY_PASSWORD        the key's password                                required with the keystore
#   RELEASE_CERT_SHA256         SHA-256 of the certificate every release must be signed with (hex, any case, with or
#                               without colons). Unset: read from the previous release's notes, which every release
#                               publishes it in; a certificate that matches neither fails the cut.
#   GH_REPO                     owner/name (default: read from the origin remote)
#   SENTRY_DSN                  optional; baked into the build exactly as release.yml does, under a fresh mapping id.
#                               The mapping is uploaded when sentry-cli is on PATH and SENTRY_AUTH_TOKEN, SENTRY_ORG
#                               and SENTRY_PROJECT are set; otherwise it is only attached to the release.
#
# Needs: git, gh (logged in, or GH_TOKEN), a JDK 17+, the Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT, or
# local.properties) with build-tools for apksigner, and network access to the repository.
set -euo pipefail

version=""
sha=""
notes_file=""
generate_notes=false
mode="local"
dry_run=false
ci_check=true
ci_timeout_minutes=20
out_dir=""
keep_worktree=false

usage() { sed -n '2,/^set -euo pipefail/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'; }
die() { echo "release-cut: $*" >&2; exit 1; }
log() { printf '\n==> %s\n' "$*"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) version="${2:-}"; shift 2 ;;
    --tag) version="${2#v}"; shift 2 ;;
    --sha) sha="${2:-}"; shift 2 ;;
    --notes-file) notes_file="${2:-}"; shift 2 ;;
    --generate-notes) generate_notes=true; shift ;;
    --mode) mode="${2:-}"; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    --no-ci-check) ci_check=false; shift ;;
    --ci-timeout) ci_timeout_minutes="${2:-}"; shift 2 ;;
    --out) out_dir="${2:-}"; shift 2 ;;
    --keep-worktree) keep_worktree=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument '$1' (see --help)" ;;
  esac
done

[[ -n "$version" ]] || die "--version X.Y.Z is required"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || die "version '$version' must look like 1.2.3 or 1.2.3-rc.1"
[[ "$mode" == "local" || "$mode" == "workflow" ]] || die "--mode must be local or workflow"
[[ -z "$notes_file" || -f "$notes_file" ]] || die "--notes-file '$notes_file' does not exist"
for tool in git gh; do command -v "$tool" >/dev/null || die "$tool is required"; done
tag="v$version"
prerelease=false
[[ "$version" == *-* ]] && prerelease=true

root="$(git rev-parse --show-toplevel)"
cd "$root"
repo="${GH_REPO:-${GITHUB_REPOSITORY:-}}"
if [[ -z "$repo" ]]; then
  origin="$(git remote get-url origin)"
  repo="$(sed -E 's#^(https://([^@/]*@)?github\.com/|git@github\.com:|ssh://git@github\.com/)##; s#\.git$##; s#/$##' <<< "$origin")"
fi
[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "could not tell the GitHub repository from '$repo'; set GH_REPO=owner/name"
out_dir="${out_dir:-$root/build/release/$tag}"

# --- The commit -------------------------------------------------------------------------------------------------------
log "Resolving the commit to release"
git fetch --quiet origin main "+refs/tags/*:refs/tags/*"
sha="$(git rev-parse --verify "${sha:-origin/main}^{commit}")"
git merge-base --is-ancestor "$sha" origin/main || die "$sha is not on origin/main; releases are cut from main"
tree="$(git rev-parse "$sha^{tree}")"
echo "commit $sha"
echo "tree   $tree"
if existing="$(git rev-parse --verify --quiet "refs/tags/$tag^{commit}")"; then
  [[ "$existing" == "$sha" ]] || die "tag $tag already exists at $existing, not at $sha"
  echo "tag    $tag already points at this commit"
fi
if gh release view "$tag" --repo "$repo" --json isDraft --jq '.isDraft' >/dev/null 2>&1; then
  $dry_run || die "a GitHub Release for $tag already exists"
fi

# --- CI evidence --------------------------------------------------------------------------------------------------------
# A run of ci.yml that concluded successfully on this commit, or on any commit with the same tree: after a
# fast-forward merge or a merge of an up-to-date pull request the pull request's head has the very tree main does,
# and its green run is proof for the merge commit too. A run still going on this commit is waited for.
ci_runs() { # $1: query string
  gh api "repos/$repo/actions/workflows/ci.yml/runs?$1" --jq '.workflow_runs[] | [.id, .status, (.conclusion // ""), .head_sha, .html_url] | @tsv'
}
ci_proof=""
ci_pending=""
if $ci_check; then
  log "Looking for the CI run that proved this tree"
  while IFS=$'\t' read -r id status conclusion head url; do
    [[ -n "$id" ]] || continue
    if [[ "$status" == "completed" && "$conclusion" == "success" ]]; then ci_proof="$url"; break; fi
    if [[ "$status" != "completed" ]]; then ci_pending="$id"; fi
  done < <(ci_runs "head_sha=$sha&per_page=20")
  if [[ -z "$ci_proof" ]]; then
    # Recent successful runs on other commits: the same tree under a different commit is the same code.
    while IFS=$'\t' read -r id status conclusion head url; do
      [[ -n "$head" && "$head" != "$sha" ]] || continue
      git cat-file -e "$head^{commit}" 2>/dev/null || git fetch --quiet origin "$head" 2>/dev/null || continue
      if [[ "$(git rev-parse --verify --quiet "$head^{tree}")" == "$tree" ]]; then ci_proof="$url (commit ${head:0:7}, identical tree)"; break; fi
    done < <(ci_runs "status=success&per_page=60")
  fi
  if [[ -n "$ci_proof" ]]; then
    echo "proved by $ci_proof"
  elif [[ -n "$ci_pending" ]]; then
    echo "CI run $ci_pending is still running on this commit; it is waited for before anything is tagged or published."
  else
    die "no successful CI run exists for this tree, and none is running on $sha. Push the commit (or open its pull request) and let CI prove it, or run lint and the unit tests yourself and pass --no-ci-check."
  fi
fi

# Waits for the pending run; prints and returns non-zero when it does not succeed.
await_ci() {
  [[ -n "$ci_pending" && -z "$ci_proof" ]] || return 0
  local deadline=$(( $(date +%s) + ci_timeout_minutes * 60 ))
  while :; do
    local line status conclusion url
    line="$(gh api "repos/$repo/actions/runs/$ci_pending" --jq '[.status, (.conclusion // ""), .html_url] | @tsv')"
    IFS=$'\t' read -r status conclusion url <<< "$line"
    if [[ "$status" == "completed" ]]; then
      if [[ "$conclusion" == "success" ]]; then ci_proof="$url"; echo "CI run succeeded: $url"; return 0; fi
      echo "release-cut: CI run $url ended with '$conclusion'; not releasing." >&2; return 1
    fi
    (( $(date +%s) < deadline )) || { echo "release-cut: CI run $url still '$status' after $ci_timeout_minutes minutes." >&2; return 1; }
    sleep 20
  done
}

# --- Workflow mode: the tag is the trigger, release.yml does the rest ----------------------------------------------
if [[ "$mode" == "workflow" ]]; then
  await_ci || exit 1
  if $dry_run; then echo "dry run: would push $tag at $sha and let release.yml publish it"; exit 0; fi
  log "Pushing $tag"
  git tag "$tag" "$sha" 2>/dev/null || true
  git push origin "refs/tags/$tag"
  log "Waiting for release.yml"
  run_id=""
  for _ in $(seq 1 30); do
    run_id="$(gh api "repos/$repo/actions/workflows/release.yml/runs?event=push&per_page=10" --jq ".workflow_runs[] | select(.head_branch == \"$tag\") | .id" | head -1)"
    [[ -n "$run_id" ]] && break
    sleep 5
  done
  [[ -n "$run_id" ]] || die "release.yml did not start for $tag; check the Actions tab"
  gh run watch "$run_id" --repo "$repo" --exit-status || die "the release run failed: https://github.com/$repo/actions/runs/$run_id"
  gh release view "$tag" --repo "$repo" --json assets --jq '.assets[].name' | grep -q '\.apk$' || die "release.yml finished but $tag has no APK; were the signing secrets configured?"
  echo "Published: https://github.com/$repo/releases/tag/$tag"
  exit 0
fi

# --- Local mode: signing material -----------------------------------------------------------------------------------
signed=false
if [[ -n "${RELEASE_KEYSTORE_FILE:-}" ]]; then
  [[ -s "$RELEASE_KEYSTORE_FILE" ]] || die "RELEASE_KEYSTORE_FILE '$RELEASE_KEYSTORE_FILE' is not a file"
  for name in RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
    [[ -n "${!name:-}" ]] || die "$name must be set together with RELEASE_KEYSTORE_FILE"
  done
  RELEASE_KEYSTORE_FILE="$(cd "$(dirname "$RELEASE_KEYSTORE_FILE")" && pwd)/$(basename "$RELEASE_KEYSTORE_FILE")"
  export RELEASE_KEYSTORE_FILE RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD
  signed=true
elif ! $dry_run; then
  die "RELEASE_KEYSTORE_FILE (with RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD) must be set to publish; --dry-run builds a debug-signed APK for verification only"
fi
# The keystore lives outside the worktree, so a keystore.properties of the working checkout never applies.

expected_cert="${RELEASE_CERT_SHA256:-}"
if [[ -z "$expected_cert" ]] && $signed; then
  expected_cert="$(gh release list --repo "$repo" --exclude-drafts --limit 20 --json tagName --jq '.[].tagName' 2>/dev/null |
    while read -r previous; do
      gh release view "$previous" --repo "$repo" --json body --jq '.body' 2>/dev/null |
        sed -n 's/^| Signing certificate (SHA-256) | `\([0-9a-fA-F:]*\)` |.*/\1/p' | head -1
    done | head -1)"
  [[ -z "$expected_cert" ]] || echo "Expecting the certificate the previous release published: $expected_cert"
fi
normalize() { local value="${1//:/}"; tr 'A-Z' 'a-z' <<< "$value"; }

# --- Build from the exact commit, in a worktree of its own ---------------------------------------------------------------
work="$(mktemp -d "${TMPDIR:-/tmp}/release-cut-$tag.XXXXXX")"
cleanup() {
  if ! $keep_worktree; then git -C "$root" worktree remove --force "$work" 2>/dev/null || rm -rf "$work"; fi
}
trap cleanup EXIT
log "Building $version from $sha in $work"
git worktree add --quiet --detach "$work" "$sha"
[[ -f "$root/local.properties" && ! -f "$work/local.properties" ]] && cp "$root/local.properties" "$work/local.properties"

# A one-off build in a throwaway worktree: no file-system watching (a daemon shared with the working checkout would
# only complain about watching a second root).
gradle_args=(--no-watch-fs -Papp.versionName="$version")
$signed || gradle_args+=(-Papp.allowUnsignedRelease=true)
mapping_uuid=""
if [[ -n "${SENTRY_DSN:-}" ]]; then
  mapping_uuid="$(uuidgen | tr 'A-Z' 'a-z')"
  gradle_args+=(-Papp.sentryDsn="$SENTRY_DSN" -Papp.sentryProguardUuid="$mapping_uuid")
fi
(cd "$work" && ./gradlew --quiet :app:assembleRelease :app:bundleRelease "${gradle_args[@]}")

# --- Package and verify ---------------------------------------------------------------------------------------------------
log "Packaging and verifying"
mkdir -p "$out_dir"
rm -f "$out_dir"/cursor-for-android-* "$out_dir"/SHA256SUMS.txt "$out_dir"/release-body.md
(cd "$work" && ./gradlew --quiet :app:printAppVersion "${gradle_args[@]}") > "$out_dir/version.txt"
version_code="$(sed -n 's/^versionCode=//p' "$out_dir/version.txt")"
resolved_name="$(sed -n 's/^versionName=//p' "$out_dir/version.txt")"
pinned_cert="$(sed -n 's/^releaseCertSha256=//p' "$out_dir/version.txt")"
[[ -n "$version_code" ]] || die "could not resolve the versionCode"
[[ "$resolved_name" == "$version" ]] || die "the build resolved versionName '$resolved_name', not '$version'"

cp "$work/app/build/outputs/apk/release/app-release.apk" "$out_dir/cursor-for-android-$version.apk"
cp "$work/app/build/outputs/bundle/release/app-release.aab" "$out_dir/cursor-for-android-$version.aab"
cp "$work/app/build/outputs/mapping/release/mapping.txt" "$out_dir/cursor-for-android-$version-mapping.txt"
(cd "$out_dir" && sha256sum -- *.apk *.aab > SHA256SUMS.txt)

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk" ]] || sdk="$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" 2>/dev/null | head -1)"
apksigner="$(ls -1 "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
[[ -x "$apksigner" ]] || die "apksigner not found under $sdk/build-tools; set ANDROID_HOME"
"$apksigner" verify --verbose --print-certs "$out_dir/cursor-for-android-$version.apk" > "$out_dir/apksigner.txt"
cert_sha256="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$out_dir/apksigner.txt" | head -1)"
[[ -n "$cert_sha256" ]] || die "apksigner reported no signer certificate"
echo "versionName $version  versionCode $version_code"
echo "signer      $cert_sha256"

if $signed; then
  [[ "$(normalize "$cert_sha256")" == "$(normalize "$pinned_cert")" ]] ||
    die "the APK is signed with $(normalize "$cert_sha256") but the build pinned $(normalize "${pinned_cert:-nothing}") as the certificate its updater trusts"
  if [[ -n "$expected_cert" ]]; then
    [[ "$(normalize "$cert_sha256")" == "$(normalize "$expected_cert")" ]] ||
      die "the APK is signed with $(normalize "$cert_sha256"), but the expected certificate is $(normalize "$expected_cert"); the wrong keystore would end in-app updates for every existing install"
    echo "The signer matches the pinned certificate."
  else
    echo "WARNING: no certificate to check the signer against (set RELEASE_CERT_SHA256); this is the first release, or the previous releases' notes carry none." >&2
  fi
else
  echo "Debug-signed (dry run): not publishable."
fi

if [[ -n "$mapping_uuid" ]]; then
  if command -v sentry-cli >/dev/null && [[ -n "${SENTRY_AUTH_TOKEN:-}" && -n "${SENTRY_ORG:-}" && -n "${SENTRY_PROJECT:-}" ]]; then
    sentry-cli upload-proguard --uuid "$mapping_uuid" --org "$SENTRY_ORG" --project "$SENTRY_PROJECT" "$out_dir/cursor-for-android-$version-mapping.txt"
  else
    echo "WARNING: SENTRY_DSN is set but the R8 mapping was not uploaded (sentry-cli with SENTRY_AUTH_TOKEN, SENTRY_ORG, SENTRY_PROJECT); it is attached to the release." >&2
  fi
fi

# --- Release body: the same header release.yml writes, then the notes ---------------------------------------------------
{
  echo "| | |"
  echo "|---|---|"
  echo "| versionName | \`${version}\` |"
  echo "| versionCode | \`${version_code}\` |"
  echo "| Commit | ${sha} |"
  if $signed; then
    echo "| Signing certificate (SHA-256) | \`${cert_sha256}\` |"
  else
    echo "| Signing | **Debug key** - dry run; not publishable. |"
  fi
  if [[ -n "$mapping_uuid" ]]; then
    echo "| Remote crash reporting | Opt-in, off by default (Settings > Privacy). Anonymous; R8 mapping id \`${mapping_uuid}\`. Crash reports are also kept on the device (Settings > Debug). |"
  else
    echo "| Remote crash reporting | Off - this build has no project to report to. Crash reports stay on the device; share them from Settings > Debug (long-press the version row). |"
  fi
  [[ -z "$ci_proof" ]] || echo "| CI | ${ci_proof%% *} |"
  echo
  echo "**Install:** download \`cursor-for-android-${version}.apk\`, then \`adb install -r cursor-for-android-${version}.apk\` (or open it on the device). \`SHA256SUMS.txt\` holds the checksums of the APK and AAB; \`*-mapping.txt\` is the R8 mapping for de-obfuscating stack traces. Installs of **v0.2.0 and later** update in place — same signing key."
  echo
  if [[ -n "$notes_file" ]]; then cat "$notes_file"; echo; fi
} > "$out_dir/release-body.md"

ls -l "$out_dir"
if $dry_run; then
  echo
  echo "Dry run: built and verified; nothing was tagged or published. Body: $out_dir/release-body.md"
  exit 0
fi

# --- Publish -----------------------------------------------------------------------------------------------------------------
# Not before CI on the commit has finished, when it was still running. Then a draft with every asset attached, published
# in a second step: publishing is what creates the tag, so release.yml (triggered by it) finds the finished release and
# stands down, and nobody ever sees a release without its APK.
await_ci || exit 1
log "Publishing $tag"
flags=(--repo "$repo" --draft --target "$sha" --title "$tag" --notes-file "$out_dir/release-body.md")
$generate_notes && flags+=(--generate-notes)
gh release create "$tag" "${flags[@]}" "$out_dir"/cursor-for-android-* "$out_dir/SHA256SUMS.txt"
publish=(--repo "$repo" --draft=false)
if $prerelease; then publish+=(--prerelease); else publish+=(--latest); fi
gh release edit "$tag" "${publish[@]}"
git fetch --quiet origin "+refs/tags/$tag:refs/tags/$tag"
echo "Published: https://github.com/$repo/releases/tag/$tag"
