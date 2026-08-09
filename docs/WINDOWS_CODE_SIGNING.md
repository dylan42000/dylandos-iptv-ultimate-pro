# Windows code signing & SmartScreen

DYLANDOS IPTV ULTIMATE ships unsigned by default. No certificates are bundled
in this repo. To reduce SmartScreen warnings and enable trusted auto-updates,
sign release builds with your own Authenticode certificate.

## electron-builder placeholders

`package.json` → `build.win` includes SHA-256 signing hooks. Signing activates
when these environment variables are set at build time:

| Variable | Purpose |
|---|---|
| `CSC_LINK` | Path to `.pfx` / `.p12` (or base64 of the file) |
| `CSC_KEY_PASSWORD` | Password for the PFX |
| `WIN_CSC_LINK` | Windows-only override of `CSC_LINK` |
| `WIN_CSC_KEY_PASSWORD` | Windows-only override of password |

Example (PowerShell, do **not** commit secrets):

```powershell
$env:CSC_LINK = "D:\certs\dylandos-codesign.pfx"
$env:CSC_KEY_PASSWORD = "***"
npm run electron:build
```

Optional certificate subject / publisher name can be set via electron-builder
`win.certificateSubjectName` when using a Windows certificate store identity
instead of a PFX file.

## SmartScreen notes

- First downloads of an **unsigned** NSIS/portable EXE often trigger
  “Windows protected your PC”. Users must click **More info → Run anyway**.
- A valid EV Authenticode cert typically establishes reputation faster than
  standard OV; either is fine for signing.
- `verifyUpdateCodeSignature` is set to `false` so unsigned builds can still
  receive GitHub Releases updates. Set it to `true` once every published
  artifact is signed, so the silent updater rejects tampered packages.

## Updater path

- Packaged builds use `electron-updater` against the GitHub `publish` block in
  `package.json` (`dylandos/iptv-ultimate`).
- Dev / unpackaged runs skip auto-check (see `setupAutoUpdater` in
  `electron/main.cjs`) to avoid noisy API errors.
- Publish `latest.yml` + NSIS blockmap with each release so silent updates work.

## Opaque window fallback (MPV embed)

If transparent HWND embedding flickers on a GPU driver, set before launch:

```powershell
$env:DYLANDOS_OPAQUE_WINDOW = "1"
```

Or persist `"opaqueWindow": true` in app settings JSON (restart required).
Default remains transparent for correct MPV `--wid` OSD compositing.
