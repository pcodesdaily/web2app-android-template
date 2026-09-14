#!/usr/bin/env bash
#
# Builds and verifies a signed release. This is the only supported way to produce
# a release artifact — it exists so two things can never be forgotten:
#
#   1. --no-configuration-cache. Gradle serialises environment-variable values
#      into .gradle/configuration-cache, and the signing password arrives that
#      way (GHSA-h3qr-39j9-4r5v: cached Gradle state leaked CI secrets).
#   2. Verification. AGP produces an UNSIGNED release when credentials are
#      missing, silently. Every artifact is checked before it can be published.
#
# Required env:
#   W2A_KEYSTORE_PATH      path to the project's upload keystore
#   W2A_KEYSTORE_PASSWORD  its password
# Optional env:
#   W2A_KEY_ALIAS          default: upload
#   W2A_KEY_FINGERPRINT    expected SHA-256; when set, a key mismatch fails the build
#   W2A_OUTPUTS            comma-separated: apk, aab (default: apk,aab)
#
# Any further arguments are passed through to Gradle, which is how per-build
# values arrive: -Pw2aApplicationId=... -Pw2aVersionCode=... etc.
#
# Usage: build-release.sh <android-template-dir> [extra gradle args...]

set -euo pipefail

TEMPLATE_DIR="${1:?usage: build-release.sh <android-template-dir> [gradle args...]}"
shift

: "${W2A_KEYSTORE_PATH:?W2A_KEYSTORE_PATH is required for a release build}"
: "${W2A_KEYSTORE_PASSWORD:?W2A_KEYSTORE_PASSWORD is required for a release build}"
[[ -f "$W2A_KEYSTORE_PATH" ]] || { echo "error: keystore not found: $W2A_KEYSTORE_PATH" >&2; exit 2; }

OUTPUTS="${W2A_OUTPUTS:-apk,aab}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

TASKS=()
[[ "$OUTPUTS" == *apk* ]] && TASKS+=("assembleRelease")
[[ "$OUTPUTS" == *aab* ]] && TASKS+=("bundleRelease")
(( ${#TASKS[@]} > 0 )) || { echo "error: W2A_OUTPUTS must name apk and/or aab" >&2; exit 2; }

cd "$TEMPLATE_DIR"

# --no-configuration-cache is not optional here; see the header.
./gradlew "${TASKS[@]}" --no-configuration-cache --console=plain "$@"

FAILED=0
verify() {
  local artifact="$1"
  [[ -f "$artifact" ]] || { echo "error: expected output missing: $artifact" >&2; FAILED=1; return; }
  bash "$SCRIPT_DIR/verify-signing.sh" "$artifact" "${W2A_KEY_FINGERPRINT:-}" || FAILED=1
}

[[ "$OUTPUTS" == *apk* ]] && verify "app/build/outputs/apk/release/app-release.apk"
[[ "$OUTPUTS" == *aab* ]] && verify "app/build/outputs/bundle/release/app-release.aab"

if (( FAILED )); then
  echo "release build FAILED verification — nothing here may be published" >&2
  exit 1
fi

echo "release build verified"
