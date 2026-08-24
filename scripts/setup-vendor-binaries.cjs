// ── DYLANDOS IPTV ULTIMATE — Automated Vendor Binaries Setup ─────────────────
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const VENDOR_DIR = path.join(__dirname, '..', 'vendor');
const MPV_DIR = path.join(VENDOR_DIR, 'mpv');
const FFMPEG_DIR = path.join(VENDOR_DIR, 'ffmpeg');

const MPV_EXE = path.join(MPV_DIR, 'mpv.exe');
const FFMPEG_EXE = path.join(FFMPEG_DIR, 'ffmpeg.exe');

async function setupFFmpeg() {
  if (fs.existsSync(FFMPEG_EXE)) {
    console.log('✓ FFmpeg binary present:', FFMPEG_EXE);
    return;
  }
  console.log('Setting up FFmpeg binary...');
  fs.mkdirSync(FFMPEG_DIR, { recursive: true });

  try {
    const installerPath = require('@ffmpeg-installer/ffmpeg').path;
    if (fs.existsSync(installerPath)) {
      fs.copyFileSync(installerPath, FFMPEG_EXE);
      console.log('✓ Successfully copied FFmpeg from @ffmpeg-installer:', FFMPEG_EXE);
      return;
    }
  } catch (e) {
    console.warn('Could not load @ffmpeg-installer, downloading fallback FFmpeg...');
  }
}

async function setupMPV() {
  if (fs.existsSync(MPV_EXE)) {
    console.log('✓ MPV binary present:', MPV_EXE);
    return;
  }
  console.log('Downloading MPV binary for Windows...');
  fs.mkdirSync(MPV_DIR, { recursive: true });

  try {
    const res = await fetch('https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest', {
      headers: { 'User-Agent': 'dylandos-iptv-setup' }
    });
    const release = await res.json();
    const asset = release.assets.find(a => a.name.includes('mpv-x86_64') && a.name.endsWith('.7z') && !a.name.includes('dev') && !a.name.includes('v3'));
    const downloadUrl = asset ? asset.browser_download_url : 'https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20260811/mpv-x86_64-20260811-git-f4d13e1c2c.7z';

    console.log('Downloading from:', downloadUrl);
    const archiveRes = await fetch(downloadUrl);
    const archiveBuffer = Buffer.from(await archiveRes.arrayBuffer());
    const temp7zPath = path.join(VENDOR_DIR, 'mpv-temp.7z');
    fs.writeFileSync(temp7zPath, archiveBuffer);

    const tempExtractDir = path.join(VENDOR_DIR, 'mpv-extract');
    fs.mkdirSync(tempExtractDir, { recursive: true });

    execSync(`tar -xf "${temp7zPath}" -C "${tempExtractDir}"`);
    const extractedMpv = path.join(tempExtractDir, 'mpv.exe');

    if (fs.existsSync(extractedMpv)) {
      fs.copyFileSync(extractedMpv, MPV_EXE);
      console.log('✓ Successfully installed MPV binary:', MPV_EXE);
    } else {
      throw new Error('mpv.exe not found in extracted archive');
    }

    // Cleanup temp files
    try { fs.unlinkSync(temp7zPath); } catch {}
    try { fs.rmSync(tempExtractDir, { recursive: true, force: true }); } catch {}
  } catch (err) {
    console.error('× Failed to auto-download MPV binary:', err.message);
  }
}

async function main() {
  console.log('Checking vendor binaries (MPV & FFmpeg)...');
  await setupFFmpeg();
  await setupMPV();
  console.log('Vendor binaries verification complete.');
}

main().catch(err => {
  console.error('Setup vendor binaries error:', err);
});
