#!/usr/bin/env node
// Placeholder block textures and drone icons for the ship shield devices, drawn pixel by pixel so
// they stay original and reproducible. Writes textures/block/ship/<id>_{top,top_on,side,bottom}.png
// for the six device blocks and textures/item/component/<family>_emitter_drone.png. The final
// models come with the effects stage; these only need to be whole and readable.
//   node tools/draw_ship_shield_textures.mjs [--preview]
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const ITEMS = join(ROOT, "src/main/resources/assets/relics_addon/textures/item/component");
const S = 16;

const hex = value => [value >> 16 & 255, value >> 8 & 255, value & 255, 255];
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const shade = (c, k) => [...c.slice(0, 3).map(v => Math.max(0, Math.min(255, Math.round(v * k)))), c[3]];
/** Deterministic hash noise in [0, 1). */
const noise = (x, y, seed) => { let h = (x * 374761393 + y * 668265263 + seed * 1274126177) | 0; h = (h ^ (h >>> 13)) * 1274126177 | 0; return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };

class Tex {
  constructor() { this.p = Array.from({ length: S * S }, () => [0, 0, 0, 0]); }
  set(x, y, c) { if (x >= 0 && y >= 0 && x < S && y < S && c) this.p[y * S + x] = c; }
  get(x, y) { return x >= 0 && y >= 0 && x < S && y < S ? this.p[y * S + x] : [0, 0, 0, 0]; }
  fill(fn) { for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) this.set(x, y, fn(x, y)); }
  rect(x, y, w, h, c) { for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) this.set(i, j, typeof c === "function" ? c(i, j) : c); }
  png() {
    const raw = Buffer.alloc((S * 4 + 1) * S);
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) this.p[y * S + x].forEach((v, i) => { raw[y * (S * 4 + 1) + 1 + x * 4 + i] = v; });
    return png(S, S, raw);
  }
}

function png(width, height, raw) {
  const table = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = bytes => { let c = ~0; for (const b of bytes) c = table[(c ^ b) & 255] ^ (c >>> 8); return (~c) >>> 0; };
  const chunk = (type, data) => {
    const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type), data]);
    const sum = Buffer.alloc(4); sum.writeUInt32BE(crc(body));
    return Buffer.concat([length, body, sum]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", header), chunk("IDAT", deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}

/** Family palettes: dark hull metal, a warm trim metal, and the glow colour of the field. */
const FAMILIES = {
  rf: { metal: hex(0x3A4048), trim: hex(0xB5713F), glow: hex(0x4FD8F0), seam: hex(0x2A7F94) },
  mana: { metal: hex(0xE6DCC4), trim: hex(0xD5A83A), glow: hex(0x52E6D0), seam: hex(0x2FA692) },
  twins: { metal: hex(0x4A3A66), trim: hex(0xCBB27A), glow: hex(0xC978FF), seam: hex(0x7F4DBF) },
};

/** Brushed plate: base metal with fine horizontal grain and a darker rim. */

/** Drone icon: a small hull with two side fins and a glowing emitter eye, outlined like the other parts. */
function drone(f) {
  const t = new Tex();
  const hull = (x, y) => shade(f.metal, 0.85 + 0.3 * noise(x, y, 5));
  t.rect(5, 4, 6, 8, hull);
  t.rect(4, 6, 1, 4, hull); t.rect(11, 6, 1, 4, hull);
  t.rect(1, 7, 3, 2, (x, y) => shade(f.trim, 0.8 + 0.2 * noise(x, y, 9)));
  t.rect(12, 7, 3, 2, (x, y) => shade(f.trim, 0.8 + 0.2 * noise(x, y, 9)));
  t.rect(6, 2, 4, 2, shade(f.trim, 0.9));
  t.rect(6, 6, 4, 4, (x, y) => { const d = Math.abs(x - 7.5) + Math.abs(y - 7.5); return d < 1.2 ? mix(f.glow, [255, 255, 255, 255], 0.5) : mix(f.glow, f.seam, d / 3); });
  t.rect(6, 12, 1, 2, f.seam); t.rect(9, 12, 1, 2, f.seam);
  // Outline
  const solid = (x, y) => t.get(x, y)[3] > 0;
  const edge = [];
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) if (!solid(x, y) && (solid(x - 1, y) || solid(x + 1, y) || solid(x, y - 1) || solid(x, y + 1))) edge.push([x, y]);
  for (const [x, y] of edge) t.set(x, y, [18, 16, 24, 255]);
  return t;
}

mkdirSync(ITEMS, { recursive: true });
const written = [];
Object.entries(FAMILIES).forEach(([name, f]) => {
  writeFileSync(join(ITEMS, `${name}_emitter_drone.png`), drone(f).png());
  written.push(`${name}_emitter_drone`);
});
console.log(`wrote ${written.length} textures`);

if (process.argv.includes("--preview")) {
  // 8x contact sheet of everything, for a look before shipping.
  const scale = 8, cols = 3, rows = Math.ceil(written.length / cols);
  const W = cols * S * scale, H = rows * S * scale;
  const raw = Buffer.alloc((W * 4 + 1) * H);
  for (let y = 0; y < H; y++) raw[y * (W * 4 + 1)] = 0;
  const all = [];
  Object.entries(FAMILIES).forEach(([name, f]) => {
    all.push(drone(f));
  });
  all.forEach((tex, i) => {
    const ox = (i % cols) * S * scale, oy = Math.floor(i / cols) * S * scale;
    for (let y = 0; y < S * scale; y++) for (let x = 0; x < S * scale; x++) {
      const c = tex.get(Math.floor(x / scale), Math.floor(y / scale));
      const px = c[3] ? c : [255, 0, 255, 255];
      const at = (oy + y) * (W * 4 + 1) + 1 + (ox + x) * 4;
      px.forEach((v, k) => { raw[at + k] = v; });
    }
  });
  mkdirSync(join(ROOT, "work"), { recursive: true });
  writeFileSync(join(ROOT, "work/ship-shield-textures.png"), png(W, H, raw));
  console.log("preview: work/ship-shield-textures.png");
}
