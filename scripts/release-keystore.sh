#!/usr/bin/env bash
#
# Creates the release signing keystore and the four GitHub Actions secrets the Release workflow expects:
#   RELEASE_KEYSTORE_BASE64, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD
#
# Usage: scripts/release-keystore.sh [--set-secrets] [keystore-path] [key-alias]
#   --set-secrets   also upload the secrets with the GitHub CLI (`gh secret set`, needs `gh auth login`)
#   keystore-path   defaults to release.jks (git-ignored)
#   key-alias       defaults to cursor-for-android
#
# Keep the keystore and password somewhere safe: every future release must be signed with this key for Android to
# accept it as an update of an installed build.
set -euo pipefail

set_secrets=false
if [[ "${1:-}" == "--set-secrets" ]]; then
  set_secrets=true
  shift
fi
keystore="${1:-release.jks}"
alias="${2:-cursor-for-android}"
dname="${KEYSTORE_DNAME:-CN=cursor-for-android}"

command -v keytool >/dev/null || { echo "keytool not found; install a JDK (17+) first." >&2; exit 1; }
if [[ -e "$keystore" ]]; then
  echo "$keystore already exists; refusing to overwrite a signing key." >&2
  exit 1
fi
if $set_secrets && ! command -v gh >/dev/null; then
  echo "--set-secrets needs the GitHub CLI (https://cli.github.com)." >&2
  exit 1
fi

read -rsp "Keystore / key password (min 6 characters): " KEYSTORE_PASSWORD
echo
read -rsp "Repeat password: " confirm
echo
[[ "$KEYSTORE_PASSWORD" == "$confirm" ]] || { echo "Passwords do not match." >&2; exit 1; }
[[ ${#KEYSTORE_PASSWORD} -ge 6 ]] || { echo "Password must be at least 6 characters." >&2; exit 1; }
export KEYSTORE_PASSWORD

# The password is handed over through the environment so it never appears in the process list.
keytool -genkeypair -v \
  -keystore "$keystore" -alias "$alias" -dname "$dname" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass:env KEYSTORE_PASSWORD -keypass:env KEYSTORE_PASSWORD

keystore_base64="$(base64 < "$keystore" | tr -d '\n')"

echo
echo "Created $keystore (alias: $alias)."
echo "Certificate SHA-256 (matches what the Release workflow prints):"
keytool -list -v -keystore "$keystore" -alias "$alias" -storepass:env KEYSTORE_PASSWORD | sed -n 's/^\s*SHA256: /  /p'

if $set_secrets; then
  echo
  echo "Uploading repository secrets with gh..."
  gh secret set RELEASE_KEYSTORE_BASE64 --body "$keystore_base64"
  gh secret set RELEASE_KEYSTORE_PASSWORD --body "$KEYSTORE_PASSWORD"
  gh secret set RELEASE_KEY_ALIAS --body "$alias"
  gh secret set RELEASE_KEY_PASSWORD --body "$KEYSTORE_PASSWORD"
  echo "Done. Push a tag (e.g. git tag v0.1.0 && git push origin v0.1.0) to publish a signed release."
else
  cat <<EOF

Add these repository secrets (Settings > Secrets and variables > Actions), or re-run with --set-secrets:

  RELEASE_KEYSTORE_BASE64    $(base64 < "$keystore" | tr -d '\n' | cut -c1-24)...   (full value: base64 < $keystore | tr -d '\n')
  RELEASE_KEYSTORE_PASSWORD  <the password you just entered>
  RELEASE_KEY_ALIAS          $alias
  RELEASE_KEY_PASSWORD       <the password you just entered>
EOF
fi

cat <<EOF

For local release builds, create a git-ignored keystore.properties next to settings.gradle.kts:

  storeFile=$keystore
  storePassword=<password>
  keyAlias=$alias
  keyPassword=<password>
EOF
