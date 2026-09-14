#!/usr/bin/env bash
#
# Verifies a release artifact before anything is published.
#
# This exists because two failure modes are silent and expensive:
#
#   1. An UNSIGNED release. AGP happily produces one when signing credentials are
#      missing. Nothing about the build output says so.
#   2. A release signed with the WRONG upload key. Play rejects it, and if it were
#      ever accepted under a different app, the two can never be reconciled.
#
# Both must fail the build, not the upload.
#
# Usage: verify-signing.sh <artifact.apk|artifact.aab> [expected-sha256-fingerprint]
#
# The fingerprint is the pin recorded when the project's upload key was generated
# (upload_key_sha256.txt). Passing it is strongly recommended; without it this
# script can only prove the artifact is signed, not that it is signed by the key
# Play already trusts.

set -euo pipefail

ARTIFACT="${1:?usage: verify-signing.sh <artifact.apk|artifact.aab> [expected-sha256]}"
EXPECTED_FINGERPRINT="${2:-}"

[[ -f "$ARTIFACT" ]] || { echo "error: no such artifact: $ARTIFACT" >&2; exit 2; }

# Normalises "a1:b2:..." / "a1b2..." / lower / upper to one comparable form.
normalise() { tr -d ': \r\n' | tr '[:upper:]' '[:lower:]'; }

case "$ARTIFACT" in
  *.apk)
    APKSIGNER="${APKSIGNER:-$(command -v apksigner || true)}"
    [[ -n "$APKSIGNER" ]] || { echo "error: apksigner not found; set APKSIGNER" >&2; exit 2; }

    # --print-certs fails outright on an unsigned APK, which is the point.
    # -v is required: without it apksigner prints no "Verified using vN scheme"
    # lines, and the scheme check below silently fails every release.
    CERTS="$("$APKSIGNER" verify -v --print-certs "$ARTIFACT" 2>&1)" || {
      echo "error: APK is not validly signed" >&2
      echo "$CERTS" >&2
      exit 1
    }

    # minSdk 24 means v2 is the floor; v1-only would be rejected by modern Play.
    if ! grep -qi "Verified using v2 scheme.*true\|Verified using v3 scheme.*true" <<<"$CERTS"; then
      echo "error: APK lacks a v2/v3 signature" >&2
      echo "$CERTS" >&2
      exit 1
    fi

    ACTUAL="$(grep -im1 "SHA-256 digest:" <<<"$CERTS" | sed 's/.*digest: *//' | normalise)"
    ;;

  *.aab)
    # An app bundle is a JAR, signed with JAR signing — the v2/v3 APK schemes do
    # not apply to it. Play re-signs the APKs it generates with the app signing
    # key, so the bundle only has to carry a valid upload signature.
    OUT="$(jarsigner -verify -verbose:summary -certs "$ARTIFACT" 2>&1)" || {
      echo "error: AAB signature did not verify" >&2
      echo "$OUT" >&2
      exit 1
    }
    grep -qi "jar verified" <<<"$OUT" || {
      echo "error: AAB is not signed" >&2
      echo "$OUT" >&2
      exit 1
    }

    # jarsigner prints the certificate but not its SHA-256, so take the fingerprint
    # from the certificate itself via keytool.
    ACTUAL="$(keytool -printcert -jarfile "$ARTIFACT" 2>/dev/null \
      | grep -im1 "SHA256:" | sed 's/.*SHA256: *//' | normalise)"
    ;;

  *)
    echo "error: expected a .apk or .aab, got: $ARTIFACT" >&2
    exit 2
    ;;
esac

[[ -n "$ACTUAL" ]] || { echo "error: could not read the signing certificate fingerprint" >&2; exit 1; }

if [[ -n "$EXPECTED_FINGERPRINT" ]]; then
  EXPECTED="$(normalise <<<"$EXPECTED_FINGERPRINT")"
  if [[ "$ACTUAL" != "$EXPECTED" ]]; then
    echo "error: signing key mismatch — this artifact would be rejected by Play" >&2
    echo "  expected $EXPECTED" >&2
    echo "  actual   $ACTUAL" >&2
    exit 1
  fi
  echo "signature verified and key matches the project's pinned upload key"
else
  echo "warning: no expected fingerprint given; signature is valid but unpinned" >&2
  echo "signature verified"
fi

echo "  artifact    $ARTIFACT"
echo "  sha256      $ACTUAL"
