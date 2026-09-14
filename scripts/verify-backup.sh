#!/usr/bin/env bash
#
# Proves a downloaded backup actually works, before the user needs it to.
#
# An untested backup is not a backup. This unpacks one, opens the keystore with
# the password it carries, and checks the certificate fingerprint matches what
# the project has on record — the same pin the build verifies against. If this
# passes, the user can recover; if it fails, they find out now rather than during
# an outage.
#
# Usage: verify-backup.sh <backup.zip> [expected-sha256]

set -euo pipefail

BUNDLE="${1:?usage: verify-backup.sh <backup.zip> [expected-sha256]}"
EXPECTED="${2:-}"

[[ -f "$BUNDLE" ]] || { echo "error: no such backup: $BUNDLE" >&2; exit 2; }

WORK="$(mktemp -d)"
# The extracted keystore and password must not outlive this check.
trap 'rm -rf "$WORK"' EXIT

# unzip is not present everywhere (notably Git Bash on Windows); Python's zipfile
# is an independent implementation and is available wherever the tooling runs.
python -c "
import zipfile, sys
with zipfile.ZipFile(sys.argv[1]) as z:
    bad = z.testzip()
    if bad: sys.exit('corrupt entry in backup: ' + bad)
    names = set(z.namelist())
    required = {'upload-keystore.p12', 'upload_certificate.pem', 'password.txt', 'README.txt'}
    missing = required - names
    if missing: sys.exit('backup is incomplete, missing: ' + ', '.join(sorted(missing)))
    z.extractall(sys.argv[2])
" "$BUNDLE" "$WORK"

KEYSTORE="$WORK/upload-keystore.p12"
W2A_KEYSTORE_PASSWORD="$(tr -d '\r\n' < "$WORK/password.txt")"
export W2A_KEYSTORE_PASSWORD

# The real test: does this password actually open this keystore?
LISTING="$(keytool -list -v -keystore "$KEYSTORE" -storetype PKCS12 -storepass:env W2A_KEYSTORE_PASSWORD 2>&1)" || {
  echo "error: the keystore in this backup could not be opened with the password it contains" >&2
  exit 1
}

ACTUAL="$(grep -im1 "SHA256:" <<<"$LISTING" | sed 's/.*SHA256: *//' | tr -d ': \r\n' | tr '[:upper:]' '[:lower:]')"
[[ -n "$ACTUAL" ]] || { echo "error: no certificate found in the keystore" >&2; exit 1; }

# Expiry matters: Play requires validity past 22 October 2033, and a backup of an
# expired key is worthless.
UNTIL="$(grep -im1 "Valid from:" <<<"$LISTING" | sed 's/.*until: *//')"

if [[ -n "$EXPECTED" ]]; then
  WANT="$(tr -d ': \r\n' <<<"$EXPECTED" | tr '[:upper:]' '[:lower:]')"
  if [[ "$ACTUAL" != "$WANT" ]]; then
    echo "error: this backup holds a DIFFERENT key than the project uses" >&2
    echo "  project  $WANT" >&2
    echo "  backup   $ACTUAL" >&2
    exit 1
  fi
fi

echo "backup verified — it opens, and the key matches the project"
echo "  sha256      $ACTUAL"
echo "  valid until ${UNTIL:-unknown}"
