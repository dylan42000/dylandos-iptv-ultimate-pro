# Gists

This folder stores per-version gist JSON files you can paste into GitHub Gists.

Usage:
- Create a new public Gist on GitHub.
- Filename: update.json
- Description suggestion: "ULTIMATE IPTV update vX.Y.Z"
- Paste the contents of the corresponding file in this folder (for example, [gists/ota-update-2.5.2.json](gists/ota-update-2.5.2.json)).
- Update `apkUrl` fields and `flavors.*.apkUrl` with your Dropbox direct download links, and update `sha256` and `apkSize` accordingly.

Filename convention:
- `ota-update-<version>.json` — JSON content used as `update.json` in a Gist.
