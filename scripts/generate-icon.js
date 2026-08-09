// Generate a simple 256x256 PNG icon programmatically
const fs = require('fs');
const zlib = require('zlib');

const WIDTH = 256;
const HEIGHT = 256;

// Create RGBA pixel data
const pixels = Buffer.alloc(WIDTH * HEIGHT * 4);

for (let y = 0; y < HEIGHT; y++) {
  for (let x = 0; x < WIDTH; x++) {
    const idx = (y * WIDTH + x) * 4;
    
    // Distance from center
    const cx = x - WIDTH / 2;
    const cy = y - HEIGHT / 2;
    const dist = Math.sqrt(cx * cx + cy * cy);
    
    // Rounded rectangle check (radius 48)
    const rx = 48;
    const inRect = (
      x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT &&
      (() => {
        const left = rx, right = WIDTH - rx, top = rx, bottom = HEIGHT - rx;
        if (x >= left && x <= right && y >= 0 && y < HEIGHT) return true;
        if (y >= top && y <= bottom && x >= 0 && x < WIDTH) return true;
        // Check corners
        const corners = [[left, top], [right, top], [left, bottom], [right, bottom]];
        for (const [ccx, ccy] of corners) {
          const d = Math.sqrt((x - ccx) ** 2 + (y - ccy) ** 2);
          if (d <= rx) return true;
        }
        return false;
      })()
    );

    if (inRect) {
      // Gradient background: dark blue-black
      const gradT = (x + y) / (WIDTH + HEIGHT);
      const r = Math.round(10 + gradT * 16);
      const g = Math.round(10 + gradT * 16);
      const b = Math.round(20 + gradT * 24);
      
      // Draw "D" letter - simplified block letter
      const letterLeft = 80, letterRight = 176, letterTop = 60, letterBottom = 196;
      const strokeW = 28;
      let isLetter = false;
      
      // Left vertical bar
      if (x >= letterLeft && x < letterLeft + strokeW && y >= letterTop && y <= letterBottom) {
        isLetter = true;
      }
      // Top horizontal
      if (y >= letterTop && y < letterTop + strokeW && x >= letterLeft && x <= letterRight - 20) {
        isLetter = true;
      }
      // Bottom horizontal
      if (y > letterBottom - strokeW && y <= letterBottom && x >= letterLeft && x <= letterRight - 20) {
        isLetter = true;
      }
      // Right curve (approximate with vertical + arcs)
      const curveCenter = { x: letterRight - 20, y: (letterTop + letterBottom) / 2 };
      const curveRadiusOuter = (letterBottom - letterTop) / 2;
      const curveRadiusInner = curveRadiusOuter - strokeW;
      const dx = x - curveCenter.x;
      const dy = y - curveCenter.y;
      const curveDist = Math.sqrt(dx * dx + dy * dy);
      if (dx >= 0 && curveDist <= curveRadiusOuter && curveDist >= curveRadiusInner) {
        isLetter = true;
      }
      
      if (isLetter) {
        // Cyan gradient for the letter
        const t = (y - letterTop) / (letterBottom - letterTop);
        pixels[idx] = Math.round(6 + t * 8);     // R
        pixels[idx + 1] = Math.round(182 - t * 20); // G
        pixels[idx + 2] = Math.round(212 + t * 20); // B
        pixels[idx + 3] = 255;
      } else {
        pixels[idx] = r;
        pixels[idx + 1] = g;
        pixels[idx + 2] = b;
        pixels[idx + 3] = 255;
      }
    } else {
      // Transparent
      pixels[idx] = 0;
      pixels[idx + 1] = 0;
      pixels[idx + 2] = 0;
      pixels[idx + 3] = 0;
    }
  }
}

// Create PNG
function createPNG(width, height, rgbaData) {
  // PNG signature
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  
  function crc32(data) {
    let crc = 0xFFFFFFFF;
    const table = new Int32Array(256);
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let j = 0; j < 8; j++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
      table[i] = c;
    }
    for (let i = 0; i < data.length; i++) {
      crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
    }
    return (crc ^ 0xFFFFFFFF) >>> 0;
  }
  
  function makeChunk(type, data) {
    const typeAndData = Buffer.concat([Buffer.from(type), data]);
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(typeAndData));
    return Buffer.concat([len, typeAndData, crc]);
  }
  
  // IHDR
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  ihdr[10] = 0; // compression
  ihdr[11] = 0; // filter
  ihdr[12] = 0; // interlace
  
  // IDAT - raw pixel data with filter bytes
  const rawData = Buffer.alloc(height * (1 + width * 4));
  for (let y = 0; y < height; y++) {
    const rowOffset = y * (1 + width * 4);
    rawData[rowOffset] = 0; // No filter
    rgbaData.copy(rawData, rowOffset + 1, y * width * 4, (y + 1) * width * 4);
  }
  const compressed = zlib.deflateSync(rawData);
  
  // IEND
  const iend = makeChunk('IEND', Buffer.alloc(0));
  
  return Buffer.concat([
    signature,
    makeChunk('IHDR', ihdr),
    makeChunk('IDAT', compressed),
    iend
  ]);
}

const png = createPNG(WIDTH, HEIGHT, pixels);
fs.writeFileSync('resources/icon.png', png);
console.log(`Created icon.png (${png.length} bytes)`);
