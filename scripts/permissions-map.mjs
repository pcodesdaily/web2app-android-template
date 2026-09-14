/**
 * Config flag to Android manifest permissions.
 *
 * Its own module so the generator and the drift check can share one definition
 * rather than one of them parsing the other's source. This mirrors
 * PERMISSION_MANIFEST in packages/config-schema; scripts/check-permissions.mjs
 * in the parent repository fails if the two disagree, so a permission added to
 * one and not the other cannot ship.
 *
 * These strings are the only permission names the build can ever emit. The
 * project configuration supplies booleans and nothing else, which is what makes
 * it impossible for a configuration to inject a permission of its choosing.
 */

/** @type {Record<string, { name: string; maxSdkVersion?: number }[]>} */
export const PERMISSIONS = {
  "camera": [{ name: "android.permission.CAMERA" }],
  "microphone": [{ name: "android.permission.RECORD_AUDIO" }],
  "location_coarse": [{ name: "android.permission.ACCESS_COARSE_LOCATION" }],
  "location_fine": [{ name: "android.permission.ACCESS_FINE_LOCATION" }],
  "notifications": [{ name: "android.permission.POST_NOTIFICATIONS" }],
  /*
   * READ_MEDIA_IMAGES and READ_MEDIA_VIDEO are API 33+. The same capability on
   * API 32 and below is READ_EXTERNAL_STORAGE, capped so it is not requested on
   * versions where READ_MEDIA_* replaced it — an uncapped declaration is both
   * useless there and a Play Console warning.
   */
  "read_media": [
    { name: "android.permission.READ_MEDIA_IMAGES" },
    { name: "android.permission.READ_MEDIA_VIDEO" },
    { name: "android.permission.READ_EXTERNAL_STORAGE", maxSdkVersion: 32 },
  ],
};
