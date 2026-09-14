/**
 * Assembles the downloadable upload-key backup.
 *
 * Usage: node scripts/make-backup.mjs <key-dir> <out.zip>
 *   key-dir  the directory generate-upload-key.sh wrote into
 *
 * Required env: W2A_KEYSTORE_PASSWORD  (the password to embed in the bundle)
 * Optional env: W2A_APP_NAME, W2A_PACKAGE_NAME, W2A_KEY_ALIAS
 *
 * The password is included on purpose. A backup the user cannot use is not a
 * backup, and splitting it across two places doubles the chance of losing one
 * half. The bundle is exactly as sensitive as the keystore itself and the README
 * says so in the first three lines.
 */

import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { makeZip, recoveryReadme } from "./backup-bundle.mjs";

const [keyDir, outPath] = process.argv.slice(2);
if (!keyDir || !outPath) {
  console.error("usage: node scripts/make-backup.mjs <key-dir> <out.zip>");
  process.exit(2);
}

const password = process.env.W2A_KEYSTORE_PASSWORD;
if (!password) {
  console.error("error: W2A_KEYSTORE_PASSWORD is required — a backup without it cannot sign anything");
  process.exit(2);
}

const keystore = readFileSync(join(keyDir, "upload-keystore.p12"));
const certificate = readFileSync(join(keyDir, "upload_certificate.pem"));
const fingerprint = readFileSync(join(keyDir, "upload_key_sha256.txt"), "utf8").trim();

const readme = recoveryReadme({
  appName: process.env.W2A_APP_NAME ?? "your app",
  packageName: process.env.W2A_PACKAGE_NAME ?? "(unknown)",
  alias: process.env.W2A_KEY_ALIAS ?? "upload",
  fingerprint,
  createdAt: new Date().toISOString().slice(0, 10),
});

const encoder = new TextEncoder();
const zip = makeZip([
  { name: "README.txt", data: encoder.encode(readme) },
  { name: "upload-keystore.p12", data: new Uint8Array(keystore) },
  { name: "upload_certificate.pem", data: new Uint8Array(certificate) },
  { name: "password.txt", data: encoder.encode(password + "\n") },
]);

writeFileSync(outPath, zip);
console.log(`backup written: ${outPath} (${zip.length} bytes)`);
console.log(`  fingerprint ${fingerprint}`);
