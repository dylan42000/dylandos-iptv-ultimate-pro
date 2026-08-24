/* ==========================================================================
   DYLANDOS IPTV ULTIMATE - Website Interactive Application Engine
   ========================================================================== */

document.addEventListener('DOMContentLoaded', () => {
  initNavbar();
  initLiveDemoPlayer();
  initLightbox();
  initDeviceTabs();
  initFaqAccordion();
  initStreamTester();
});

/* 1. Navbar Scroll Effect */
function initNavbar() {
  const navbar = document.querySelector('.navbar');
  if (!navbar) return;
  window.addEventListener('scroll', () => {
    if (window.scrollY > 50) {
      navbar.classList.add('scrolled');
    } else {
      navbar.classList.remove('scrolled');
    }
  });
}

/* 2. Interactive Live Demo Player Simulator */
function initLiveDemoPlayer() {
  const channelItems = document.querySelectorAll('.channel-item');
  const demoTitle = document.getElementById('demo-current-title');
  const demoHudFps = document.getElementById('hud-fps');
  const demoHudBitrate = document.getElementById('hud-bitrate');
  const demoHudPing = document.getElementById('hud-ping');
  const demoVideoContainer = document.getElementById('demo-video-container');

  if (!demoVideoContainer) return;

  // Video backgrounds or simulated visual canvas
  const channelsData = {
    '1': { name: 'HBO HD — Premier Movies', bg: 'images/vod-movies.png', fps: '60 FPS', bitrate: '14.2 Mbps', ping: '12 ms' },
    '2': { name: 'ESPN 4K — Live Champions League', bg: 'images/live-tv.png', fps: '60 FPS', bitrate: '18.5 Mbps', ping: '9 ms' },
    '3': { name: 'Sky Sports 4K — F1 Grand Prix', bg: 'images/multiscreen.png', fps: '60 FPS', bitrate: '21.0 Mbps', ping: '11 ms' },
    '4': { name: 'Discovery Ultra — Planet Earth', bg: 'images/hero.png', fps: '60 FPS', bitrate: '16.8 Mbps', ping: '14 ms' }
  };

  channelItems.forEach(item => {
    item.addEventListener('click', () => {
      channelItems.forEach(i => i.classList.remove('active'));
      item.classList.add('active');

      const chId = item.getAttribute('data-ch');
      const data = channelsData[chId];
      if (data) {
        if (demoTitle) demoTitle.textContent = data.name;
        if (demoHudFps) demoHudFps.textContent = data.fps;
        if (demoHudBitrate) demoHudBitrate.textContent = data.bitrate;
        if (demoHudPing) demoHudPing.textContent = data.ping;

        const imgEl = demoVideoContainer.querySelector('.simulated-video-bg');
        if (imgEl) {
          imgEl.style.opacity = '0.3';
          setTimeout(() => {
            imgEl.src = data.bg;
            imgEl.style.opacity = '1';
          }, 150);
        }
      }
    });
  });

  // Toggle Multi-Screen quad mode in demo
  const quadBtn = document.getElementById('btn-toggle-quad');
  if (quadBtn) {
    let isQuad = false;
    quadBtn.addEventListener('click', () => {
      isQuad = !isQuad;
      const imgEl = demoVideoContainer.querySelector('.simulated-video-bg');
      if (isQuad) {
        quadBtn.textContent = '❌ Exit 4-Screen Quad Mode';
        quadBtn.style.background = '#ff007f';
        quadBtn.style.color = '#fff';
        if (imgEl) imgEl.src = 'images/multiscreen.png';
        if (demoTitle) demoTitle.textContent = '4-Way Quad View (Multi-Stream Active)';
      } else {
        quadBtn.textContent = '🖥️ Toggle 4-Screen Multi-View';
        quadBtn.style.background = 'rgba(255, 255, 255, 0.08)';
        quadBtn.style.color = '#fff';
        const activeItem = document.querySelector('.channel-item.active');
        if (activeItem) {
          activeItem.click();
        } else {
          if (imgEl) imgEl.src = 'images/vod-movies.png';
          if (demoTitle) demoTitle.textContent = 'HBO HD — Premier Movies';
        }
      }
    });
  }
}

/* 3. Lightbox Screenshot Viewer */
function initLightbox() {
  const lightbox = document.getElementById('lightbox');
  const lightboxImg = document.getElementById('lightbox-img');
  const lightboxTitle = document.getElementById('lightbox-title');
  const lightboxClose = document.getElementById('lightbox-close');
  const galleryItems = document.querySelectorAll('.gallery-item');

  if (!lightbox) return;

  galleryItems.forEach(item => {
    item.addEventListener('click', () => {
      const imgSrc = item.getAttribute('data-img');
      const title = item.getAttribute('data-title');
      if (lightboxImg) {
        lightboxImg.src = imgSrc;
        if (lightboxTitle) lightboxTitle.textContent = title || 'DYLANDOS IPTV ULTIMATE Screenshot';
        lightbox.classList.add('active');
        document.body.style.overflow = 'hidden';
      }
    });
  });

  if (lightboxClose) {
    lightboxClose.addEventListener('click', closeLightbox);
  }

  lightbox.addEventListener('click', (e) => {
    if (e.target === lightbox) closeLightbox();
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && lightbox.classList.contains('active')) {
      closeLightbox();
    }
  });

  function closeLightbox() {
    lightbox.classList.remove('active');
    document.body.style.overflow = 'auto';
  }
}

/* 4. Device Compatibility Tabs Switcher */
function initDeviceTabs() {
  const tabBtns = document.querySelectorAll('.tab-btn');
  const tabContents = document.querySelectorAll('.tab-content');

  tabBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      const tabId = btn.getAttribute('data-tab');
      if (!tabId) return;

      tabBtns.forEach(b => b.classList.remove('active'));
      tabContents.forEach(c => c.classList.remove('active'));

      btn.classList.add('active');
      const targetContent = document.getElementById(`tab-${tabId}`);
      if (targetContent) targetContent.classList.add('active');
    });
  });
}

/* 5. FAQ Accordion */
function initFaqAccordion() {
  const faqItems = document.querySelectorAll('.faq-item');

  faqItems.forEach(item => {
    const question = item.querySelector('.faq-question');
    if (!question) return;
    question.addEventListener('click', () => {
      const isOpen = item.classList.contains('active');
      faqItems.forEach(i => i.classList.remove('active'));
      if (!isOpen) {
        item.classList.add('active');
      }
    });
  });
}

/* 6. M3U / Xtream Stream Tester Simulator */
function initStreamTester() {
  const btnTest = document.getElementById('btn-test-stream');
  const inputUrl = document.getElementById('test-m3u-url');
  const resultsDiv = document.getElementById('tester-results');

  if (btnTest && inputUrl && resultsDiv) {
    btnTest.addEventListener('click', () => {
      const url = inputUrl.value.trim();
      if (!url) {
        alert('Please enter an M3U playlist URL or Xtream Codes server address!');
        return;
      }

      btnTest.disabled = true;
      btnTest.textContent = '⚡ Analyzing M3U Stream...';

      setTimeout(() => {
        btnTest.disabled = false;
        btnTest.textContent = '🚀 Test Stream Speed';
        resultsDiv.style.display = 'block';
        resultsDiv.innerHTML = `
          <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px;">
            <strong style="color: var(--primary-emerald); font-size: 1.1rem;">✅ Playlist Validated Successfully!</strong>
            <span style="background: rgba(0, 255, 136, 0.2); color: var(--primary-emerald); padding: 2px 10px; border-radius: 99px; font-size: 0.8rem; font-weight: 700;">Parsed in 0.08s</span>
          </div>
          <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; font-family: var(--font-mono); font-size: 0.85rem; color: var(--text-muted);">
            <div><strong>Channels:</strong> <span style="color: #fff;">14,520</span></div>
            <div><strong>VOD Movies:</strong> <span style="color: #fff;">42,800</span></div>
            <div><strong>EPG Sync:</strong> <span style="color: var(--primary-cyan);">100% Match</span></div>
            <div><strong>Estimated Speed:</strong> <span style="color: var(--primary-emerald);">980 Mbps</span></div>
          </div>
        `;
      }, 900);
    });
  }
}
