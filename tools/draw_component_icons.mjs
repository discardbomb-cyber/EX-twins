#!/usr/bin/env node
// 16x16 item icons for the crafting components, drawn pixel by pixel so they stay original and
// reproducible. Writes textures/item/component/<name>.png and, with --preview, an 8x contact
// sheet to work/component-icons.png.
//   node tools/draw_component_icons.mjs [--preview]
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "src/main/resources/assets/relics_addon/textures/item/component");
const S = 16;

const hex = value => [value >> 16 & 255, value >> 8 & 255, value & 255, 255];
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const shade = (c, k) => [...c.slice(0, 3).map(v => Math.max(0, Math.min(255, Math.round(v * k)))), c[3]];

class Icon {
  constructor() { this.p = Array.from({ length: S * S }, () => [0, 0, 0, 0]); }
  set(x, y, c) { if (x >= 0 && y >= 0 && x < S && y < S && c) this.p[y * S + x] = c; }
  get(x, y) { return x >= 0 && y >= 0 && x < S && y < S ? this.p[y * S + x] : [0, 0, 0, 0]; }
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
  /** Lit sphere: light from the upper left, with a specular dot. */
  sphere(cx, cy, r, base, { spec = true, rim } = {}) {
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const nx = (x + .5 - cx) / r, ny = (y + .5 - cy) / r, d = nx * nx + ny * ny;
      if (d > 1) continue;
      const nz = Math.sqrt(1 - d), light = Math.max(0, -.45 * nx - .55 * ny + .7 * nz);
      let c = shade(base, .45 + .85 * light);
      if (rim && d > .72) c = mix(c, rim, .55);
      if (spec && light > .93) c = mix(c, [255, 255, 255, 255], .6);
      this.set(x, y, c);
    }
  }
  /** Dark outline around every opaque pixel, like vanilla item art. */
  outline(color = [18, 16, 24, 255]) {
    const solid = (x, y) => this.get(x, y)[3] > 0;
    const edge = [];
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      if (solid(x, y)) continue;
      if (solid(x - 1, y) || solid(x + 1, y) || solid(x, y - 1) || solid(x, y + 1)) edge.push([x, y]);
    }
    for (const [x, y] of edge) this.set(x, y, color);
  }
  png() {
    const raw = Buffer.alloc((S * 4 + 1) * S);
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) this.p[y * S + x].forEach((v, i) => { raw[y * (S * 4 + 1) + 1 + x * 4 + i] = v; });
    return png(S, S, raw, 6);
  }
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

const ICONS = {
  /** Resonant circuit: a small board with gold pads, cyan traces and a black chip. */
  resonant_circuit() {
    const i = new Icon();
    i.rect(2, 3, 12, 10, (x, y) => (x + y) % 5 === 0 ? hex(0x2c7a4b) : hex(0x23633d));
    i.rect(2, 3, 12, 1, hex(0x3d9a61));
    for (const x of [3, 6, 9, 12]) { i.set(x, 3, hex(0xe8c255)); i.set(x, 12, hex(0xc99a2e)); }
    i.line(3, 5, 6, 5, hex(0x55f2ff)); i.line(6, 5, 6, 7, hex(0x55f2ff));
    i.line(12, 10, 10, 10, hex(0x55f2ff)); i.line(10, 10, 10, 9, hex(0x55f2ff));
    i.line(3, 10, 5, 10, hex(0x2fb4c9));
    i.rect(6, 6, 4, 4, hex(0x16181d));
    i.rect(7, 7, 2, 2, hex(0x3a3f48));
    for (const [x, y] of [[5, 7], [5, 8], [10, 7], [10, 8], [7, 5], [8, 5], [7, 10], [8, 10]]) i.set(x, y, hex(0xb8bec7));
    i.set(7, 7, hex(0x9ff8ff));
    i.outline();
    return i;
  },
  /** Energy cell: a copper-red battery with a glowing charge window and a steel terminal. */
  energy_cell() {
    const i = new Icon();
    i.rect(6, 1, 4, 2, (x) => x === 6 ? hex(0xe6eaef) : hex(0xaab1ba));
    i.rect(4, 3, 8, 12, (x, y) => shade(hex(0xb4502c), x < 6 ? 1.25 : x > 9 ? .7 : 1));
    i.rect(4, 3, 8, 1, hex(0xd9d4cf)); i.rect(4, 14, 8, 1, hex(0x8f97a1));
    i.rect(6, 5, 4, 8, hex(0x2a0e08));
    for (let y = 5; y < 13; y++) i.rect(6, y, 4, 1, mix(hex(0xffe066), hex(0xff5a2a), (y - 5) / 7));
    for (const [x, y] of [[8, 6], [7, 7], [8, 8], [7, 9], [8, 10], [7, 11]]) i.set(x, y, hex(0xfffbe6));
    i.outline();
    return i;
  },
  /** Mana cell: a cyan crystal held in a gold cradle. */
  mana_cell() {
    const i = new Icon();
    const crystal = [[8, 1], [7, 2], [9, 2], [6, 3], [10, 3], [5, 4], [11, 4], [5, 5], [11, 5], [5, 6], [11, 6], [5, 7], [11, 7], [6, 8], [10, 8], [7, 9], [9, 9], [8, 10]];
    for (let y = 1; y <= 10; y++) for (let x = 5; x <= 11; x++) {
      const w = y <= 4 ? y - 1 : y <= 7 ? 3 : 10 - y;
      if (Math.abs(x - 8) <= w) i.set(x, y, mix(hex(0x8ff6ff), hex(0x1c6fd0), (y + (x - 5) * .4) / 12));
    }
    for (const [x, y] of crystal) i.set(x, y, hex(0x0f3f86));
    i.set(7, 3, hex(0xffffff)); i.set(7, 4, hex(0xc9fbff)); i.set(6, 5, hex(0xc9fbff));
    i.rect(4, 11, 9, 2, (x) => shade(hex(0xd8a53a), x < 7 ? 1.2 : .9));
    i.rect(5, 13, 7, 2, hex(0x9c6c1d));
    i.set(3, 10, hex(0xe8c255)); i.set(13, 10, hex(0xe8c255));
    i.outline();
    return i;
  },
  /** RF shield core: a dark faceted sphere with glowing cyan edges. */
  rf_shield_core() {
    const i = new Icon();
    i.sphere(8, 8, 6.6, hex(0x2a3442), { spec: false });
    const glow = hex(0x3fe8ff), dim = hex(0x1d9fb8);
    i.line(8, 2, 3, 7, glow); i.line(8, 2, 13, 7, glow); i.line(3, 7, 8, 9, dim); i.line(13, 7, 8, 9, dim);
    i.line(8, 9, 8, 14, glow); i.line(3, 7, 5, 12, dim); i.line(13, 7, 11, 12, dim); i.line(5, 12, 8, 14, dim); i.line(11, 12, 8, 14, dim);
    i.set(8, 9, hex(0xe8ffff)); i.set(8, 2, hex(0xe8ffff));
    i.outline();
    return i;
  },
  /** Mana shield core: a navy orb in a gold ring with a small blue diamond. */
  mana_shield_core() {
    const i = new Icon();
    i.sphere(8, 8, 5.4, hex(0x1a2a5c));
    for (let a = 0; a < 64; a++) {
      const t = a / 64 * Math.PI * 2, x = Math.round(8 + Math.cos(t) * 7), y = Math.round(8 + Math.sin(t) * 2.6);
      if (!(Math.sin(t) < 0 && Math.abs(Math.cos(t)) < .7)) i.set(x, y, Math.cos(t) > 0 ? hex(0xf0c65a) : hex(0xb8862b));
    }
    i.set(8, 6, hex(0x9ff4ff)); i.set(7, 7, hex(0x9ff4ff)); i.set(9, 7, hex(0x9ff4ff)); i.set(8, 8, hex(0x9ff4ff)); i.set(8, 7, hex(0x36b4ff));
    i.rect(7, 2, 3, 1, hex(0xd9a53d)); i.rect(7, 13, 3, 1, hex(0xb8862b));
    i.outline();
    return i;
  },
  /** Twins shield core: a grey hex-plated sphere circled by violet beads. */
  twins_shield_core() {
    const i = new Icon();
    // Back half of the bead ring, then the plated sphere, then the front half over it.
    const ring = (front) => {
      for (let a = 0; a < 90; a++) {
        const t = a / 90 * Math.PI * 2, x = Math.round(8 + Math.cos(t) * 7.2), y = Math.round(8.5 + Math.sin(t) * 4.2 - Math.cos(t) * 1.6);
        if ((Math.sin(t) > 0) === front) i.set(x, y, front ? hex(0xd9a53d) : hex(0x8a6424));
      }
      for (let a = 0; a < 12; a++) {
        const t = a / 12 * Math.PI * 2 + .26, x = Math.round(8 + Math.cos(t) * 7.2), y = Math.round(8.5 + Math.sin(t) * 4.2 - Math.cos(t) * 1.6);
        if ((Math.sin(t) > 0) !== front) continue;
        i.set(x, y, front ? hex(0x9b45ea) : hex(0x4a2472));
        if (front) i.set(x, y - 1, hex(0xd7a6ff));
      }
    };
    ring(false);
    i.sphere(8, 8, 5.2, hex(0xa3a9b3));
    for (const [x, y] of [[6, 5], [9, 5], [4, 8], [7, 7], [10, 8], [6, 10], [9, 10], [12, 6]]) {
      i.set(x, y, hex(0x575c66)); i.set(x + 1, y, hex(0x6c717b));
    }
    ring(true);
    i.outline();
    return i;
  },
  /** RF drone frame: a folded gunmetal drone with an orange panel and a cyan eye. */
  rf_drone_frame() {
    const i = new Icon();
    // Hawken-style scout seen from above: a tapered hull, two swept wing panels and a hot thruster.
    const hull = hex(0x626b77), wing = hex(0x4a525d), edge = hex(0x8d97a3), trim = hex(0xc98a3a);
    i.rect(6, 5, 4, 7, (x, y) => shade(hull, x === 6 ? 1.25 : x === 9 ? .8 : 1));
    i.rect(7, 3, 2, 2, hull); i.set(7, 2, edge);
    for (let k = 0; k < 4; k++) {
      i.line(5 - k, 7 + k, 5, 7 + k, wing); i.line(10, 7 + k, 10 + k, 7 + k, wing);
    }
    i.line(2, 10, 5, 7, edge); i.line(13, 10, 10, 7, edge);
    i.rect(2, 11, 3, 1, trim); i.rect(11, 11, 3, 1, trim);
    i.rect(7, 7, 2, 2, trim);
    i.set(7, 4, hex(0x4ff0ff)); i.set(8, 4, hex(0xb8fbff));
    i.rect(7, 12, 2, 1, hex(0x2c3139)); i.rect(7, 13, 2, 1, hex(0x4ff0ff)); i.set(7, 14, hex(0x1d9fb8)); i.set(8, 14, hex(0x1d9fb8));
    i.outline();
    return i;
  },
  /** Mana drone shell: three ivory-and-gold petals around a blue light. */
  mana_drone_shell() {
    const i = new Icon();
    for (const [angle, tone] of [[-Math.PI / 2, 1.1], [Math.PI / 6, .95], [5 * Math.PI / 6, .85]]) {
      for (let r = 2; r <= 6; r++) for (let w = -2; w <= 2; w++) {
        if (Math.abs(w) > (r < 5 ? 2 : 1)) continue;
        const x = Math.round(8 + Math.cos(angle) * r - Math.sin(angle) * w * .8);
        const y = Math.round(8 + Math.sin(angle) * r + Math.cos(angle) * w * .8);
        i.set(x, y, shade(Math.abs(w) === 2 || r === 6 ? hex(0xd9a53d) : hex(0xe8e2d2), tone));
      }
    }
    i.sphere(8, 8, 2, hex(0x2f9bff));
    i.set(8, 8, hex(0xc9f4ff));
    i.outline();
    return i;
  },
  /** Twins drone plate: a black pentagon with a violet sigil. */
  twins_drone_plate() {
    const i = new Icon();
    const corners = Array.from({ length: 5 }, (_, k) => [8 + Math.cos(-Math.PI / 2 + k * 2 * Math.PI / 5) * 6.6, 8.4 + Math.sin(-Math.PI / 2 + k * 2 * Math.PI / 5) * 6.6]);
    const inside = (x, y) => corners.every((a, k) => {
      const b = corners[(k + 1) % 5];
      return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]) >= 0;
    });
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) if (inside(x + .5, y + .5)) i.set(x, y, shade(hex(0x1b1724), 1 + (8 - y) * .03));
    for (let k = 0; k < 5; k++) {
      const a = corners[k], b = corners[(k + 1) % 5];
      i.line(Math.round(a[0]), Math.round(a[1]), Math.round(b[0]), Math.round(b[1]), k < 2 ? hex(0x6b4f8f) : hex(0x3a2d52));
    }
    // Sigil: a ring with three spokes running to the plate's corners.
    const v = hex(0xa24df0), l = hex(0xe0b8ff);
    for (const [x, y] of [[7, 6], [8, 6], [9, 7], [9, 8], [8, 9], [7, 9], [6, 8], [6, 7]]) i.set(x, y, v);
    i.line(8, 5, 8, 3, v); i.line(6, 9, 4, 11, v); i.line(9, 9, 11, 11, v);
    i.set(8, 3, l); i.set(4, 11, l); i.set(11, 11, l); i.set(7, 7, l);
    i.outline();
    return i;
  },
  /** Device module: a slim cartridge with a violet crystal window and gold contacts. */
  device_module() {
    const i = new Icon();
    i.rect(3, 4, 10, 8, (x, y) => shade(hex(0x3a404a), y === 4 ? 1.35 : y === 11 ? .75 : 1));
    i.rect(5, 6, 6, 4, hex(0x14111c));
    i.rect(6, 7, 4, 2, (x) => x < 8 ? hex(0xc98cff) : hex(0x8a3ee0));
    i.set(6, 7, hex(0xf2e0ff));
    for (const x of [4, 6, 8, 10]) { i.set(x, 12, hex(0xe8c255)); i.set(x, 13, hex(0xb8862b)); }
    i.rect(12, 5, 1, 2, hex(0x4ff0ff));
    i.outline();
    return i;
  },
};

mkdirSync(OUT, { recursive: true });
const icons = Object.entries(ICONS).map(([name, draw]) => [name, draw()]);
for (const [name, icon] of icons) writeFileSync(join(OUT, `${name}.png`), icon.png());
console.log(`Wrote ${icons.length} component icons to ${OUT}`);

if (process.argv.includes("--preview")) {
  const scale = 8, gap = 4, width = icons.length * (S * scale + gap) + gap, height = S * scale + gap * 2;
  const raw = Buffer.alloc((width * 3 + 1) * height);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const o = y * (width * 3 + 1) + 1 + x * 3, checker = ((x >> 3) + (y >> 3)) % 2 ? 70 : 90;
    raw[o] = raw[o + 1] = raw[o + 2] = checker;
  }
  icons.forEach(([, icon], n) => {
    const ox = gap + n * (S * scale + gap);
    for (let y = 0; y < S * scale; y++) for (let x = 0; x < S * scale; x++) {
      const [r, g, b, a] = icon.get(Math.floor(x / scale), Math.floor(y / scale));
      if (!a) continue;
      const o = (y + gap) * (width * 3 + 1) + 1 + (x + ox) * 3;
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = b;
    }
  });
  mkdirSync(join(ROOT, "work"), { recursive: true });
  writeFileSync(join(ROOT, "work/component-icons.png"), png(width, height, raw, 2));
  console.log("Preview: work/component-icons.png");
}
