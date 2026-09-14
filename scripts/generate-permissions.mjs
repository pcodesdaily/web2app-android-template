/**
 * Writes the app's permissions into the manifest.
 *
 * The manifest has carried a W2A_PERMISSIONS marker and a comment describing
 * this step since the template was written, and nothing ever filled it in. So
 * every switch in the dashboard's Permissions section did nothing: an app whose
 * site asked for the camera could not get it, however the project was
 * configured.
 *
 * Two properties matter here, and both are structural rather than careful:
 *
 *   - **Nothing from the configuration is ever written into the manifest.** The
 *     config supplies booleans; the permission strings come from the closed map
 *     below. A hostile config can turn a permission on or off — which is its
 *     job — but cannot inject a `<uses-permission>` of its choosing, or any
 *     other XML.
 *   - **The map is checked against the schema.** scripts/check-permissions.mjs
 *     in the parent repository fails if this map and PERMISSION_MANIFEST
 *     disagree, so a permission added to one and not the other cannot ship.
 */

import { readFileSync, writeFileSync } from "node:fs";
import { PERMISSIONS } from "./permissions-map.mjs";

const CONFIG = "app/src/main/assets/config.json";
const MANIFEST = "app/src/main/AndroidManifest.xml";
const MARKER = "W2A_PERMISSIONS";

const config = JSON.parse(readFileSync(CONFIG, "utf8"));
const requested = config?.permissions ?? {};

const lines = [];
for (const [flag, entries] of Object.entries(PERMISSIONS)) {
  // Strictly true. A truthy string in a hand-edited config must not silently
  // grant a permission the user never enabled.
  if (requested[flag] !== true) continue;

  for (const entry of entries) {
    const cap = entry.maxSdkVersion ? ` android:maxSdkVersion="${entry.maxSdkVersion}"` : "";
    lines.push(`    <uses-permission android:name="${entry.name}"${cap} />`);
  }
}

const manifest = readFileSync(MANIFEST, "utf8");

/*
 * The marker is an XML comment spanning several lines. Replacing the whole
 * comment — rather than inserting after it — means running this twice produces
 * the same file, which matters because the runner may rebuild a checkout that
 * already has a manifest from a previous run.
 */
const markerPattern = new RegExp(`[ \\t]*<!--\\s*${MARKER}:[\\s\\S]*?-->\\n?`, "m");
if (!markerPattern.test(manifest)) {
  console.error(`::error::${MANIFEST} has no ${MARKER} marker — cannot place permissions.`);
  process.exit(1);
}

const block = lines.length > 0
  ? `${lines.join("\n")}\n`
  : `    <!-- No additional permissions requested. -->\n`;

writeFileSync(MANIFEST, manifest.replace(markerPattern, block), "utf8");

console.log(
  lines.length > 0
    ? `Declared ${lines.length} permission${lines.length === 1 ? "" : "s"}:\n${lines.join("\n")}`
    : "No additional permissions requested.",
);

/*
 * Play policy, said out loud in the build log.
 *
 * READ_MEDIA_IMAGES and READ_MEDIA_VIDEO are restricted: Google requires a
 * declared, reviewable use case. A WebView app almost never needs them, because
 * <input type="file"> goes through the system picker, which grants access to
 * the chosen file without any storage permission at all.
 */
if (requested.read_media === true) {
  console.log(
    "::warning::Media permissions are restricted by Google Play policy and usually are not needed —" +
      " file uploads work through the system picker without them. Turn this off unless your site" +
      " genuinely reads the photo library.",
  );
}
