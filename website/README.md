# 🌟 DYLANDOS IPTV ULTIMATE — Web Portal & Website Guide

Welcome to the official web portal for **DYLANDOS IPTV ULTIMATE (v4.8.1)**!

This directory (`website/`) is a 100% standalone, zero-dependency static web application designed to showcase the DYLANDOS IPTV ULTIMATE desktop & Firestick app.

---

## 🚀 How to Run & Preview the Website

### Option 1: Direct Local Server (Recommended for instant local access)
Run either of these commands in your command prompt / terminal from the root workspace directory:

```bash
# Using Node.js serve
npx serve website -p 8085
```

Or using Python:
```bash
# Using Python builtin HTTP server
python -m http.server 8085 --directory website
```

Then open your browser and navigate to:
👉 **`http://localhost:8085`**

---

### Option 2: Direct File Opening
Double-click `index.html` inside the `website/` directory to open it in Chrome, Edge, Firefox, or Brave.

---

## 📁 Website Folder Structure

```text
website/
├── index.html            # Main HTML5 portal application
├── styles.css            # Futuristic glassmorphism CSS aesthetics
├── app.js                # High-performance, zero-dependency interactive JS engine
├── README.md             # Complete running & deployment instructions
├── DEPLOYMENT_GUIDE.md   # Step-by-step Netlify, Vercel & GitHub Pages guide
└── images/               # App showcase screenshots & iconography
    ├── hero.png
    ├── live-tv.png
    ├── vod-movies.png
    ├── firestick-tv.png
    ├── multiscreen.png
    ├── speed-engine.png
    └── icon.png
```

---

## ⚡ Interactive Features Built-in

1. **Interactive Demo Player**: Test live channel switching, multi-view 4-way split toggling, and stream FPS/bitrate metrics directly in your web browser.
2. **Lightbox Screenshot Gallery**: Click any screenshot for high-resolution visual previews.
3. **M3U Playlist Speed Tester**: Input any M3U playlist or Xtream Codes URL to simulate instant parsing.
4. **Multi-Device Guides**: Dedicated tabs for Windows PC setup, Amazon Firestick (Downloader code `948210`), and Android TV APK.
5. **Feature Matrix**: Side-by-side performance comparison vs TiviMate, IPTV Smarters, and VLC.

---

## 🌐 Deploying Live to the Internet (Free 60-Second Setup)

To publish this website live on the web:

1. **Netlify Drop (Easiest & Free)**:
   - Go to [app.netlify.com/drop](https://app.netlify.com/drop)
   - Drag & drop the `website` folder directly into your browser window.
   - Live URL generated instantly with free SSL!

2. **GitHub Pages (Free)**:
   - Push the repo to GitHub.
   - Go to **Settings -> Pages** -> Select branch `main` and directory `/website`.

3. **Vercel**:
   - Run `npx vercel website` or import repo in [Vercel Dashboard](https://vercel.com).
