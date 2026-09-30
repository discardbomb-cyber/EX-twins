#!/usr/bin/env node
// 16x16 block textures for the ship hives, drawn pixel by pixel so they stay original and reproducible: a launch
// face whose emblem shows how many drones work as one (a hexagon round one light for the aegis, two linked rings
// for the escort, three lights round a focus for the lance), armoured sides with a light strip in the hive's family
// colour, and a back with an FE port. Writes textures/block/<id>_{front,side,back}.png and, with --preview, an 8x
// contact sheet to work/ship-hive-textures.png.
//   node tools/draw_ship_hive_textures.mjs [--preview]
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "src/main/resources/assets/relics_addon/textures/block");
const S = 16;

const hex = value => [value >> 16 & 255, value >> 8 & 255, value & 255, 255];
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * Math.max(0, Math.min(1, t))));
const shade = (c, k) => [...c.slice(0, 3).map(v => Math.max(0, Math.min(255, Math.round(v * k)))), c[3]];
const WHITE = [255, 255, 255, 255];

const METAL = hex(0x3b4049), METAL_DARK = hex(0x262a31), METAL_LIGHT = hex(0x5d6571), RIVET = hex(0x7c8592), RECESS = hex(0x121418);

class Tex {
  constructor() { this.p = Array.from({ length: S * S }, () => [0, 0, 0, 255]); }
  set(x, y, c) { if (x >= 0 && y >= 0 && x < S && y < S && c) this.p[y * S + x] = c; }
  get(x, y) { return this.p[Math.max(0, Math.min(S - 1, y)) * S + Math.max(0, Math.min(S - 1, x))]; }
  rect(x, y, w, h, c) { for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) this.set(i, j, typeof c === "function" ? c(i, j) : c); }
  line(x0, y0, x1, y1, c) {
    let dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, e = dx + dy;
    for (;;) {
      this.set(x0, y0, c);
      if (x0 === x1 && y0 === y1) break;
      const e2 = 2 * e;
      if (e2 >= dy) { e += dy; x0 += sx; }
      if (e2 <= dx) { e += dx; y0 += sy; }
    }
  }
  /** Adds light of colour {@code c} round (cx, cy), fading over {@code r} pixels. */
  glow(cx, cy, r, c, strength = 1) {
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const d = Math.hypot(x + .5 - cx, y + .5 - cy) / r;
      if (d >= 1) continue;
      this.set(x, y, mix(this.get(x, y), c, (1 - d) * (1 - d) * strength));
    }
  }
  png() {
    const raw = Buffer.alloc((S * 4 + 1) * S);
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) this.p[y * S + x].forEach((v, i) => { raw[y * (S * 4 + 1) + 1 + x * 4 + i] = v; });
    return png(S, S, raw, 6);
  }
}

/** A hull plate: brushed gunmetal, bevelled edges and a rivet in each corner. */
function plate() {
  const t = new Tex();
  t.rect(0, 0, S, S, (x, y) => shade(METAL, 1.06 - y * .012 + ((x * 7 + y * 13) % 5 === 0 ? -.05 : 0)));
  t.rect(0, 0, S, 1, METAL_LIGHT); t.rect(0, 0, 1, S, shade(METAL_LIGHT, .92));
  t.rect(0, S - 1, S, 1, METAL_DARK); t.rect(S - 1, 0, 1, S, METAL_DARK);
  for (const [x, y] of [[2, 2], [13, 2], [2, 13], [13, 13]]) { t.set(x, y, RIVET); t.set(x + 1, y + 1, METAL_DARK); }
  return t;
}

function side(color) {
  const t = plate();
  // A recessed light strip across the middle, hottest at its centre.
  t.rect(3, 6, 10, 4, RECESS);
  t.rect(3, 6, 10, 1, METAL_DARK);
  for (let x = 4; x < 12; x++) {
    const hot = 1 - Math.abs(x - 7.5) / 5;
    t.set(x, 7, mix(color, WHITE, .15 + .35 * hot)); t.set(x, 8, shade(color, .75 + .25 * hot));
  }
  t.rect(3, 10, 10, 1, METAL_LIGHT);
  // Vents under the strip.
  for (const x of [4, 7, 10]) t.rect(x, 12, 2, 1, METAL_DARK);
  return t;
}

function back() {
  const t = plate();
  // An FE port: a dark socket with copper contacts and a red-and-dark marker.
  t.rect(5, 5, 6, 6, RECESS);
  t.rect(5, 5, 6, 1, METAL_DARK); t.rect(5, 10, 6, 1, METAL_LIGHT);
  t.rect(6, 7, 1, 2, hex(0xd08a4a)); t.rect(9, 7, 1, 2, hex(0xd08a4a));
  t.set(7, 6, hex(0xb8322c)); t.set(8, 6, hex(0x7a1f1b));
  return t;
}

/** The launch face: an iris round a dark bay, with the kind's emblem lit inside. */
function front(color, emblem) {
  const t = plate();
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const dx = x + .5 - 8, dy = y + .5 - 8, d = Math.hypot(dx, dy);
    if (d < 6.6 && d >= 5.4) {
      // Iris blades: alternating shades round the rim.
      const blade = Math.floor((Math.atan2(dy, dx) + Math.PI) / (Math.PI / 4)) % 2;
      t.set(x, y, blade ? METAL_LIGHT : shade(METAL, .85));
    } else if (d < 5.4) t.set(x, y, mix(RECESS, color, .08));
  }
  emblem(t, color);
  return t;
}

const EMBLEMS = {
  /** One: a hexagon round a single light. */
  aegis(t, color) {
    const corners = Array.from({ length: 6 }, (_, k) => [8 + Math.cos(Math.PI / 6 + k * Math.PI / 3) * 3.6, 8 + Math.sin(Math.PI / 6 + k * Math.PI / 3) * 3.6]);
    for (let k = 0; k < 6; k++) {
      const a = corners[k], b = corners[(k + 1) % 6];
      t.line(Math.round(a[0] - .5), Math.round(a[1] - .5), Math.round(b[0] - .5), Math.round(b[1] - .5), shade(color, .8));
    }
    t.glow(8, 8, 3, color, .9);
    t.rect(7, 7, 2, 2, mix(color, WHITE, .55));
  },
  /** Two: a pair of linked rings. */
  escort(t, color) {
    for (const cx of [5.5, 10.5]) {
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const d = Math.hypot(x + .5 - cx, y + .5 - 8);
        if (d < 2.1 && d >= 1.1) t.set(x, y, shade(color, .85));
      }
      t.glow(cx, 8, 2.2, color, .8);
    }
    t.line(7, 7, 8, 7, mix(color, WHITE, .4)); t.line(7, 8, 8, 8, shade(color, .7));
    t.set(5, 7, mix(color, WHITE, .5)); t.set(10, 7, mix(color, WHITE, .5));
  },
  /** Three: three lights round a focus, their beams meeting in it. */
  lance(t, color) {
    const points = [0, 1, 2].map(k => [8 + Math.cos(-Math.PI / 2 + k * 2 * Math.PI / 3) * 3.4, 8 + Math.sin(-Math.PI / 2 + k * 2 * Math.PI / 3) * 3.4]);
    for (const [x, y] of points) t.line(Math.floor(x), Math.floor(y), 8, 8, shade(color, .8));
    for (const [x, y] of points) { t.glow(x, y, 2.4, color, 1); t.set(Math.floor(x), Math.floor(y), mix(color, WHITE, .6)); }
    t.glow(8, 8, 2.2, color, 1);
    t.set(7, 7, mix(color, WHITE, .7)); t.set(8, 8, mix(color, WHITE, .5));
  },
};

const HIVES = [
  ["aegis_hive", hex(0x42e6c8), EMBLEMS.aegis],
  ["escort_hive", hex(0x38e8ff), EMBLEMS.escort],
  ["lance_hive", hex(0xb151ff), EMBLEMS.lance],
];

mkdirSync(OUT, { recursive: true });
const textures = [];
for (const [id, color, emblem] of HIVES) {
  for (const [part, tex] of [["front", front(color, emblem)], ["side", side(color)], ["back", back()]]) {
    writeFileSync(join(OUT, `${id}_${part}.png`), tex.png());
    textures.push(tex);
  }
}
console.log(`Wrote ${textures.length} ship hive textures to ${OUT}`);

if (process.argv.includes("--preview")) {
  const scale = 8, gap = 4, columns = 3, rows = HIVES.length;
  const width = columns * (S * scale + gap) + gap, height = rows * (S * scale + gap) + gap;
  const raw = Buffer.alloc((width * 3 + 1) * height);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const o = y * (width * 3 + 1) + 1 + x * 3, checker = ((x >> 3) + (y >> 3)) % 2 ? 70 : 90;
    raw[o] = raw[o + 1] = raw[o + 2] = checker;
  }
  textures.forEach((tex, n) => {
    const ox = gap + (n % columns) * (S * scale + gap), oy = gap + Math.floor(n / columns) * (S * scale + gap);
    for (let y = 0; y < S * scale; y++) for (let x = 0; x < S * scale; x++) {
      const [r, g, b] = tex.get(Math.floor(x / scale), Math.floor(y / scale));
      const o = (y + oy) * (width * 3 + 1) + 1 + (x + ox) * 3;
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = b;
    }
  });
  mkdirSync(join(ROOT, "work"), { recursive: true });
  writeFileSync(join(ROOT, "work/ship-hive-textures.png"), png(width, height, raw, 2));
  console.log("Preview: work/ship-hive-textures.png");
}

function png(width, height, raw, colorType) {
  const table = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = bytes => { let c = ~0; for (const b of bytes) c = table[(c ^ b) & 255] ^ (c >>> 8); return (~c) >>> 0; };
  const chunk = (type, data) => {
    const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type), data]);
    const sum = Buffer.alloc(4); sum.writeUInt32BE(crc(body));
    return Buffer.concat([length, body, sum]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = colorType;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", header), chunk("IDAT", deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}
