#!/usr/bin/env bash
#
# Generates a Play **upload key** for one project.
#
# Read this before changing anything here:
#
#   Play App Signing is mandatory for apps created after August 2021. Google holds
#   the *app signing key* that signs what users install; this script creates the
#   *upload key*, which only proves to Play that an upload came from this project.
#   A lost upload key is recoverable — the developer requests an upload key reset
#   and keeps publishing. That is the whole reason we generate an upload key and
#   never an app signing key: the unrecoverable key stays Google's problem.
#
#   What is NOT recoverable is signing a project's releases with a *different*
#   upload key than Play has on record, silently. Hence: this script refuses to
#   overwrite an existing keystore, and prints a SHA-256 fingerprint that the
#   build pins against.
#
# Secrets come from the environment, never from arguments: anything on argv is
# visible to every process on the machine via the process list.
#
# Required env:
#   W2A_KEYSTORE_PASSWORD  password for the keystore and key (>= 12 chars)
# Optional env:
#   W2A_KEY_ALIAS          default: upload
#   W2A_DNAME              default: CN=<app name>, OU=Web2App
#   W2A_APP_NAME           used to build the default DNAME
#
# Usage: generate-upload-key.sh <output-directory>

set -euo pipefail

OUT_DIR="${1:?usage: generate-upload-key.sh <output-directory>}"
ALIAS="${W2A_KEY_ALIAS:-upload}"
APP_NAME="${W2A_APP_NAME:-Web2App}"

KEYSTORE="$OUT_DIR/upload-keystore.p12"
CERT_PEM="$OUT_DIR/upload_certificate.pem"
FINGERPRINT_FILE="$OUT_DIR/upload_key_sha256.txt"

# RSA 2048 is Play's documented minimum for an upload key; 4096 matches what
# Google generates for its own app signing keys.
KEY_SIZE=4096
# ~30 years. Play requires validity beyond 22 October 2033.
VALIDITY_DAYS=10950

if [[ -z "${W2A_KEYSTORE_PASSWORD:-}" ]]; then
  echo "error: W2A_KEYSTORE_PASSWORD is not set" >&2
  exit 2
fi

if (( ${#W2A_KEYSTORE_PASSWORD} < 12 )); then
  echo "error: W2A_KEYSTORE_PASSWORD must be at least 12 characters" >&2
  exit 2
fi

# Overwriting a keystore that has already signed a release is the one mistake
# that cannot be undone from inside this script, so it is refused outright.
if [[ -e "$KEYSTORE" ]]; then
  echo "error: $KEYSTORE already exists — refusing to overwrite an upload key" >&2
  exit 3
fi

mkdir -p "$OUT_DIR"

# keytool's -storepass:env form keeps the password out of argv. It is undocumented
# in -help but supported; verified against the JDK in use.
keytool -genkeypair \
  -storetype PKCS12 \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize "$KEY_SIZE" \
  -validity "$VALIDITY_DAYS" \
  -dname "${W2A_DNAME:-CN=$APP_NAME, OU=Web2App}" \
  -storepass:env W2A_KEYSTORE_PASSWORD \
  -keypass:env W2A_KEYSTORE_PASSWORD \
  >/dev/null

# Play needs this certificate to enrol the upload key, and again to authorise an
# upload key reset later. Losing it is inconvenient; generating it now is free.
keytool -exportcert -rfc \
  -alias "$ALIAS" \
  -keystore "$KEYSTORE" \
  -storepass:env W2A_KEYSTORE_PASSWORD \
  -file "$CERT_PEM" \
  >/dev/null

# The pin the build verifies against, so a build can never quietly ship an app
# signed by a key other than the one Play already trusts.
keytool -list -v \
  -alias "$ALIAS" \
  -keystore "$KEYSTORE" \
  -storepass:env W2A_KEYSTORE_PASSWORD \
  | grep -i "SHA256:" \
  | head -1 \
  | sed 's/.*SHA256: *//' \
  | tr -d '\r' > "$FINGERPRINT_FILE"

chmod 600 "$KEYSTORE" "$CERT_PEM" 2>/dev/null || true

echo "upload key created"
echo "  keystore    $KEYSTORE"
echo "  certificate $CERT_PEM"
echo "  sha256      $(cat "$FINGERPRINT_FILE")"
