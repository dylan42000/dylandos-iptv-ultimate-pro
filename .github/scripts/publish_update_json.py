#!/usr/bin/env python3
"""C7: Regenerate update.json from a signed release and PATCH the live OTA gist.

Run by .github/workflows/android-release.yml after a tag release. Reads the
GitHub Release assets (URLs), the computed SHA-256 files, and writes the gist's
update.json via the GitHub API. The app refuses payloads without SHA-256, so
this script always fills real hashes.

Env required:
  GITHUB_REPOSITORY, RELEASE_TAG, GIST_TOKEN, GIST_ID, VERSION, VERSION_CODE
Cwd: directory containing the APKs + *.sha256 files.
"""
import json
import os
import sys
import urllib.request

def die(msg):
    print(f"publish_update_json: {msg}", file=sys.stderr)
    sys.exit(1)

repo = os.environ.get("GITHUB_REPOSITORY")
release_tag = os.environ.get("RELEASE_TAG")
token = os.environ.get("GIST_TOKEN")
gist_id = os.environ.get("GIST_ID")
version = os.environ.get("VERSION")
version_code_raw = os.environ.get("VERSION_CODE")
if not all([repo, release_tag, token, gist_id, version, version_code_raw]):
    die("missing required env (GITHUB_REPOSITORY, RELEASE_TAG, GIST_TOKEN, GIST_ID, VERSION, VERSION_CODE)")
try:
    version_code = int(version_code_raw)
except ValueError:
    die(f"VERSION_CODE not an int: {version_code_raw!r}")

HEADERS = {
    "Authorization": f"Bearer {token}",
    "Accept": "application/vnd.github+json",
    "User-Agent": "dylandos-ota-publisher",
}

def api(path: str, method: str = "GET", payload: dict | None = None):
    req = urllib.request.Request(
        f"https://api.github.com{path}",
        data=json.dumps(payload).encode() if payload is not None else None,
        method=method,
        headers={**HEADERS, **({"Content-Type": "application/json"} if payload is not None else {})},
    )
    with urllib.request.urlopen(req) as r:
        body = r.read()
        return json.loads(body) if body else {}

# 1. Resolve the uploaded asset URLs from the release.
release = api(f"/repos/{repo}/releases/tags/{release_tag}")
assets = {a["name"]: a["browser_download_url"] for a in release.get("assets", [])}
for name in ("app-firestick-release.apk", "app-premium-release.apk"):
    if name not in assets:
        die(f"asset missing on release: {name}")

def sha_file(name: str) -> str:
    try:
        with open(name) as f:
            return f.read().strip()
    except OSError as e:
        die(f"cannot read {name}: {e}")

def size_of(name: str) -> int:
    try:
        return os.path.getsize(name)
    except OSError as e:
        die(f"cannot stat {name}: {e}")

fire = {
    "apkUrl": assets["app-firestick-release.apk"],
    "apkSize": size_of("app-firestick-release.apk"),
    "sha256": sha_file("firestick.sha256"),
}
premium = {
    "apkUrl": assets["app-premium-release.apk"],
    "apkSize": size_of("app-premium-release.apk"),
    "sha256": sha_file("premium.sha256"),
}

payload = {
    "versionCode": version_code,
    "version": version,
    "mandatory": False,
    "minRequiredVersionCode": 0,
    "changelog": [f"Release {version} — see the changelog on the release page."],
    "flavors": {"firestick": fire, "premium": premium},
    "apkUrl": fire["apkUrl"],
    "apkSize": fire["apkSize"],
    "sha256": fire["sha256"],
}

# 2. PATCH the gist's update.json with the new payload.
gist_patch = {"files": {"update.json": {"content": json.dumps(payload, indent=2)}}}
api(f"/gists/{gist_id}", method="PATCH", payload=gist_patch)
print(f"gist {gist_id} updated: v{version} (code {version_code}), firestick sha={fire['sha256'][:12]}…")
