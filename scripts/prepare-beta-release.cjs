// Run only after both signed APK builds have passed verification.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const version = '5.3.0';
const info = {
  versionCode: 101, version, mandatory: false, minRequiredVersionCode: 0,
  changelog: [
    'TIMESHIFT: Seekable local MPEG-TS rolling buffer; pause/rewind controls and startup watchdog corrected.',
    'GUIDE: Program selection with left/right, selected-program options, reminders, and refreshed time windows up to seven days where supplied.',
    'HOME: Compact shortcuts, recent additions, configurable sections, and optional TMDB-ranked recent titles matched to your catalog.',
    'METADATA: Cached TMDB scores and trailers, optional IMDb/Rotten Tomatoes via OMDb, primary-language setting, and faster detail rendering.',
    'BROWSING: Remember Live TV category selections and try alternate movie/series categories when the initial category is empty or unavailable.',
    'NAVIGATION: Movies and Series open on categories; improved return focus, grid scrolling, and category-to-poster navigation.',
    'SEARCH: Removed unstable lazy-list focus restoration, preserved result position and section, deduplicated results, and moved matching off the UI thread.',
    'LAYOUT: Removed oversized browse headers so Live TV, Movies, and Series have more room on Firestick and Android Premium; sidebar now scrolls and shows labels.',
    'DVR: Keep recording accounts within the current provider; release failed recording resources, preserve concurrent recording state, and handle low storage.',
    'TIMESHIFT: Bounded Media3 memory, safer low-space cache sizing, seek-end clamping, consistent pause state, and local rewind preferred over provider restart.',
    'RELEASE: Updated Android build tooling for Kotlin/R8 compatibility and removed duplicate architecture payloads from each APK.'
  ],
  flavors: {},
};
fs.mkdirSync(path.join(root, 'release'), { recursive: true });
for (const flavor of ['firestick', 'premium']) {
  const directory = path.join(root, 'android/app/build/outputs/apk', flavor, 'release');
  const metadata = JSON.parse(fs.readFileSync(path.join(directory, 'output-metadata.json'), 'utf8'));
  if (metadata.elements[0].versionCode !== 101 || !metadata.elements[0].versionName.startsWith(version)) {
    throw new Error(`Refusing stale ${flavor} APK`);
  }
  const apk = fs.readFileSync(path.join(directory, metadata.elements[0].outputFile));
  info.flavors[flavor] = {
    apkUrl: `REPLACE_WITH_SIGNED_${flavor.toUpperCase()}_APK_DIRECT_URL`,
    apkSize: apk.length,
    sha256: crypto.createHash('sha256').update(apk).digest('hex'),
  };
  fs.writeFileSync(path.join(root, `release/DYLANDOS-IPTV-${version}-${flavor}.apk`), apk);
}
const json = JSON.stringify(info, null, 2) + '\n';
fs.writeFileSync(path.join(root, `gists/ota-update-${version}.json`), json);
fs.writeFileSync(path.join(root, `android/OTA_GIST_TEMPLATE_${version}.json`), json);
console.log(json);
