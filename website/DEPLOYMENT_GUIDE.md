# 🚀 Step-by-Step Deployment & Upload Guide

This website is designed to be **100% standalone, lightweight, and easy to deploy**. It requires no build tools, no complex server dependencies, and no node_modules.

Below are the easiest ways to publish this website live on the internet in less than 60 seconds:

---

## ⚡ Method 1: Netlify (Easiest 1-Click Drag & Drop — FREE)

1. Go to [https://app.netlify.com/drop](https://app.netlify.com/drop) in your web browser.
2. Open your File Explorer to this project directory.
3. **Drag and drop the `website` folder** directly onto the Netlify page.
4. Your website will instantly build and go live with a free SSL certificate (`https://your-site-name.netlify.app`)!

---

## ⚡ Method 2: Vercel (Free & Super Fast)

1. Sign up/Log in at [https://vercel.com](https://vercel.com).
2. Install Vercel CLI (optional) or use the Vercel Dashboard:
   ```bash
   npx vercel website
   ```
3. Follow the 3 prompts, and Vercel will deploy your website live within seconds!

---

## ⚡ Method 3: GitHub Pages (Free Automatic Hosting)

If your repository is hosted on GitHub:
1. Push the `website` folder to your GitHub repository:
   ```bash
   git add website/
   git commit -m "Add world-class website"
   git push origin main
   ```
2. On GitHub, go to your repository **Settings** -> **Pages**.
3. Under **Build and deployment** -> **Source**, select `Deploy from a branch`.
4. Choose the `main` branch and specify `/website` directory (or publish root).
5. Click **Save**. GitHub will host your site live at `https://<your-username>.github.io/<repo-name>/website/`.

---

## ⚡ Method 4: Shared Web Hosting / cPanel / FTP (GoDaddy, Hostinger, Namecheap, Bluehost)

1. Connect to your web hosting account using **cPanel File Manager** or an FTP client like **FileZilla**.
2. Navigate to your domain's web root folder (usually `public_html` or `www`).
3. Upload all files from inside the `website` folder (`index.html`, `styles.css`, `app.js`, and the `images/` directory).
4. Visit your domain name (e.g., `https://yourdomain.com`) in your browser to view your live website!

---

## ⚡ Method 5: Local Server Hosting

If you want to host it locally on your computer or home server:

### Using Node.js:
```bash
npx serve website -p 8080
```

### Using Python:
```bash
python -m http.server 8080 --directory website
```

### Using Docker / Nginx:
```bash
docker run -d -p 80:80 -v "%cd%\website:/usr/share/nginx/html:ro" nginx:alpine
```
