/**
 * Builds the upload-key backup a user downloads and keeps.
 *
 * The bundle is deliberately self-sufficient. A keystore on its own is a brick:
 * without the password it cannot sign, and without upload_certificate.pem the
 * user cannot request a Play upload key reset. All three travel together, with
 * instructions, because the moment someone needs this they are already having a
 * bad day and will not be piecing files together from three places.
 *
 * Store-only ZIP written by hand: there is no zip binary on every build host, GNU
 * tar cannot write zip, and the Worker that will eventually serve this download
 * has no shell at all. ~50 lines beats a dependency for a format this simple.
 */

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[i] = c >>> 0;
  }
  return table;
})();

function crc32(bytes) {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** DOS time/date, which is what the ZIP format stores. */
function dosDateTime(date) {
  const time = (date.getHours() << 11) | (date.getMinutes() << 5) | (Math.floor(date.getSeconds() / 2));
  const day = ((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  return { time, day };
}

/**
 * @param {{name: string, data: Uint8Array}[]} entries
 * @returns {Uint8Array} a store-only ZIP
 */
export function makeZip(entries, now = new Date()) {
  const { time, day } = dosDateTime(now);
  const encoder = new TextEncoder();
  const locals = [];
  const centrals = [];
  let offset = 0;

  for (const entry of entries) {
    const name = encoder.encode(entry.name);
    const crc = crc32(entry.data);
    const size = entry.data.length;

    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true); // local file header signature
    local.setUint16(4, 20, true); // version needed
    local.setUint16(6, 0, true); // flags
    local.setUint16(8, 0, true); // method: stored
    local.setUint16(10, time, true);
    local.setUint16(12, day, true);
    local.setUint32(14, crc, true);
    local.setUint32(18, size, true); // compressed size
    local.setUint32(22, size, true); // uncompressed size
    local.setUint16(26, name.length, true);
    local.setUint16(28, 0, true); // extra length
    locals.push(new Uint8Array(local.buffer), name, entry.data);

    const central = new DataView(new ArrayBuffer(46));
    central.setUint32(0, 0x02014b50, true); // central directory signature
    central.setUint16(4, 20, true); // version made by
    central.setUint16(6, 20, true); // version needed
    central.setUint16(8, 0, true);
    central.setUint16(10, 0, true);
    central.setUint16(12, time, true);
    central.setUint16(14, day, true);
    central.setUint32(16, crc, true);
    central.setUint32(20, size, true);
    central.setUint32(24, size, true);
    central.setUint16(28, name.length, true);
    central.setUint16(30, 0, true);
    central.setUint16(32, 0, true); // comment length
    central.setUint16(34, 0, true); // disk number
    central.setUint16(36, 0, true); // internal attrs
    central.setUint32(38, 0, true); // external attrs
    central.setUint32(42, offset, true); // offset of local header
    centrals.push(new Uint8Array(central.buffer), name);

    offset += 30 + name.length + size;
  }

  const centralSize = centrals.reduce((n, part) => n + part.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true); // end of central directory
  end.setUint16(8, entries.length, true);
  end.setUint16(10, entries.length, true);
  end.setUint32(12, centralSize, true);
  end.setUint32(16, offset, true);

  const parts = [...locals, ...centrals, new Uint8Array(end.buffer)];
  const total = parts.reduce((n, part) => n + part.length, 0);
  const out = new Uint8Array(total);
  let at = 0;
  for (const part of parts) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

/**
 * The file someone reads when their build has stopped working and they are
 * looking for the fastest way out. Kept to pure ASCII on purpose: it may be
 * opened in any editor on any machine, and mojibake in recovery instructions is
 * the last thing anyone needs. Written for that moment: what this is, what
 * to do first, and the reassurance that a lost upload key is survivable.
 */
export function recoveryReadme({ appName, packageName, alias, fingerprint, createdAt }) {
  return `UPLOAD KEY BACKUP - ${appName}

Package name   ${packageName}
Key alias      ${alias}
SHA-256        ${fingerprint}
Created        ${createdAt}

KEEP THIS SOMEWHERE SAFE AND PRIVATE.
Anyone holding these files and the password can upload builds that Google Play
will accept as coming from you.

WHAT IS IN HERE
  upload-keystore.p12       your upload key
  upload_certificate.pem    the certificate Play needs to identify that key
  password.txt              the keystore password
  README.txt                this file

WHAT THIS KEY IS (AND IS NOT)
Google Play holds the *app signing key* that signs what users install. You hold
this *upload key*, which only proves to Play that an upload came from you. That
distinction is the good news below.

IF YOU LOSE THIS BACKUP
You do not lose your app. Generate a new upload key, then ask Google to switch
to it: Play Console > Test and release > Setup > App integrity > App signing >
Request upload key reset. Google verifies you and swaps the upload key. Users
notice nothing, because the app signing key never changes.

IF YOU THINK SOMEONE ELSE HAS THIS BACKUP
Treat it as compromised and request an upload key reset immediately, as above.

WHAT YOU MUST NEVER DO
Do not change the package name (${packageName}). Play treats a different package
name as a different app, and no key can undo that.

RESTORING
To check this backup is intact, run:
  keytool -list -v -keystore upload-keystore.p12 -storetype PKCS12
and confirm the SHA256 fingerprint matches the one above.
`;
}
