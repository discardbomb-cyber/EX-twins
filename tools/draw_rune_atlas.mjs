#!/usr/bin/env node
// The Mana runes: an atlas of 28 glyphs of our own script and 4 ornaments, drawn stroke by stroke from a
// fixed seed so it is original and reproducible. Each glyph is a tapered calligraphic body (a stem, an arc,
// a chevron or a looped stem) with branches, hooks and marks grown off it; the ornaments are concentric
// rings, a toothed ring, a petal curl and a bead. White on transparent with a soft glow round every stroke,
// in an 8x4 grid of 64-pixel cells, for additive drawing tinted by the vertex colour. Writes
// textures/misc/mana_runes.png and, with --preview, a contact sheet to work/rune-atlas.png.
//   node tools/draw_rune_atlas.mjs [--preview]
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "src/main/resources/assets/relics_addon/textures/misc/mana_runes.png");
const CELL = 64, COLUMNS = 8, ROWS = 4, SUPER = 4, S = CELL * SUPER;
/** Glyphs of the script, then the ornaments; the game reads the same layout (ManaRunes). */
const GLYPHS = 28, ORNAMENTS = ["rings", "gear", "curl", "bead"];

function random(seed) {
  let state = seed * 0x9E3779B1 >>> 0 || 1;
  return () => {
    state = state + 0x6D2B79F5 >>> 0;
    let t = Math.imul(state ^ state >>> 15, 1 | state);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}

/**
 * One cell's ink. Dabs of the nib are noted as they are drawn and laid down at the end, the whole glyph fitted
 * inside the cell's margin (so no stroke or glow bleeds into a neighbour), at four times the cell's size and
 * brought down to it, so every edge is smooth.
 */
class Cell {
  constructor(fit = true) { this.ink = new Float32Array(S * S); this.dabs = []; this.fit = fit; }
  /** A round nib pressed at (x, y) (cell units, 0..1) with radius r. */
  dab(x, y, r) { this.dabs.push([x, y, r]); }
  press(x, y, r) {
    const cx = x * S, cy = y * S, R = Math.max(.6, r * S);
    for (let py = Math.max(0, Math.floor(cy - R)); py <= Math.min(S - 1, Math.ceil(cy + R)); py++) {
      for (let px = Math.max(0, Math.floor(cx - R)); px <= Math.min(S - 1, Math.ceil(cx + R)); px++) {
        const d = Math.hypot(px + .5 - cx, py + .5 - cy);
        if (d <= R) this.ink[py * S + px] = 1;
      }
    }
  }
  /** A stroke along {@code path(t)} (t 0..1), {@code width(t)} wide: dabs close enough to leave no gaps. */
  stroke(path, width, steps = 240) {
    for (let k = 0; k <= steps; k++) {
      const t = k / steps, [x, y] = path(t);
      this.dab(x, y, width(t) / 2);
    }
  }
  /** Down to the cell's own size, then a soft glow laid under every stroke. */
  finish() {
    let left = 1, top = 1, right = 0, bottom = 0;
    for (const [x, y, r] of this.dabs) { left = Math.min(left, x - r); right = Math.max(right, x + r); top = Math.min(top, y - r); bottom = Math.max(bottom, y + r); }
    const room = .74, scale = this.fit ? Math.min(1, room / Math.max(right - left, bottom - top, 1e-3)) : 1;
    const dx = this.fit ? .5 - (left + right) / 2 * scale : 0, dy = this.fit ? .5 - (top + bottom) / 2 * scale : 0;
    for (const [x, y, r] of this.dabs) this.press(x * scale + dx, y * scale + dy, r * Math.max(.8, scale));
    const cover = new Float32Array(CELL * CELL);
    for (let y = 0; y < CELL; y++) for (let x = 0; x < CELL; x++) {
      let sum = 0;
      for (let j = 0; j < SUPER; j++) for (let i = 0; i < SUPER; i++) sum += this.ink[(y * SUPER + j) * S + x * SUPER + i];
      cover[y * CELL + x] = sum / (SUPER * SUPER);
    }
    const halo = blur(blur(cover, 2.4, true), 2.4, false);
    return cover.map((c, i) => Math.min(1, c + .55 * halo[i] * (1 - c)));
  }
}

function blur(values, sigma, horizontal) {
  const radius = Math.ceil(sigma * 3), weights = [];
  for (let k = -radius; k <= radius; k++) weights.push(Math.exp(-k * k / (2 * sigma * sigma)));
  const total = weights.reduce((a, b) => a + b, 0), out = new Float32Array(values.length);
  for (let y = 0; y < CELL; y++) for (let x = 0; x < CELL; x++) {
    let sum = 0;
    for (let k = -radius; k <= radius; k++) {
      const xx = horizontal ? x + k : x, yy = horizontal ? y : y + k;
      if (xx < 0 || yy < 0 || xx >= CELL || yy >= CELL) continue;
      sum += values[yy * CELL + xx] * weights[k + radius];
    }
    out[y * CELL + x] = sum / total;
  }
  return out;
}

// --- paths and nibs (cell units, y down) ----------------------------------------------------------

const line = (a, b) => t => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
const bezier = (a, b, c, d) => t => {
  const s = 1 - t;
  return [s * s * s * a[0] + 3 * s * s * t * b[0] + 3 * s * t * t * c[0] + t * t * t * d[0], s * s * s * a[1] + 3 * s * s * t * b[1] + 3 * s * t * t * c[1] + t * t * t * d[1]];
};
const arc = (cx, cy, r, from, to) => t => [cx + r * Math.cos(from + (to - from) * t), cy + r * Math.sin(from + (to - from) * t)];
/** A brush's swell: thin at both ends and full in the middle, never thinner than a hair. */
const taper = (full, ends = .35) => t => full * (ends + (1 - ends) * Math.sin(Math.PI * t) ** .6);
const even = w => () => w;

// --- the script ------------------------------------------------------------------------------------

/**
 * Glyph {@code index}: a body (by turns a stem, an arc, a chevron, a looped stem, a wave, a fork or a hooked
 * stem), one to three branches off it (straight, hooked or ending in a dot), and a mark or two beside it.
 */
function glyph(index) {
  const r = random(index * 7919 + 17), cell = new Cell();
  const jitter = amount => (r() - .5) * 2 * amount;
  const main = .068 + jitter(.008);
  let spine;
  switch (index % 7) {
    case 4: { // A wave, like a drawn-out S.
      const x = .5 + jitter(.05), swing = .16 + jitter(.04), flip = r() < .5 ? 1 : -1;
      spine = bezier([x - flip * swing, .18], [x + flip * swing * 1.8, .36], [x - flip * swing * 1.8, .64], [x + flip * swing, .82]);
      break;
    }
    case 5: { // A fork: a stem splitting in two at its head (or its foot).
      const x = .5 + jitter(.04), split = .45 + jitter(.06), up = r() < .5;
      const foot = [x, up ? .84 : .16], fork = [x + jitter(.03), split], wide = .2 + jitter(.04);
      cell.stroke(line(fork, [x - wide, up ? .18 : .82]), taper(main * .85, .4));
      cell.stroke(line(fork, [x + wide, up ? .18 : .82]), taper(main * .85, .4));
      spine = line(foot, fork);
      break;
    }
    case 6: { // A stem with its foot hooked round.
      const x = .54 + jitter(.05), side = r() < .5 ? 1 : -1;
      spine = t => t < .72 ? line([x, .17], [x, .66])(t / .72) : arc(x - side * .12, .66, .12, side > 0 ? 0 : Math.PI, side > 0 ? Math.PI * .95 : Math.PI * .05)((t - .72) / .28);
      break;
    }
    case 0: { // A stem, bowed a little.
      const x = .5 + jitter(.06), bow = jitter(.09);
      spine = bezier([x + jitter(.04), .17], [x + bow, .38], [x - bow, .62], [x + jitter(.04), .83]);
      break;
    }
    case 1: { // An open arc, facing one way or the other.
      const facing = r() < .5 ? 1 : -1, cx = .5 - facing * .1, radius = .3 + jitter(.03);
      spine = arc(cx, .5, radius, -Math.PI / 2 - facing * .25, Math.PI / 2 + facing * .25);
      if (facing < 0) { const inner = spine; spine = t => { const [x, y] = inner(t); return [1 - x, y]; }; }
      break;
    }
    case 2: { // A chevron, pointing up or down, drawn as one stroke through its point.
      const up = r() < .5, spread = .24 + jitter(.05), apex = [.5 + jitter(.05), up ? .18 : .82];
      const a = [.5 - spread, up ? .82 : .18], b = [.5 + spread, up ? .82 : .18];
      spine = t => t < .5 ? line(a, apex)(t * 2) : line(apex, b)((t - .5) * 2);
      break;
    }
    default: { // A stem with a teardrop loop at its head.
      const x = .5 + jitter(.05);
      cell.stroke(line([x, .36], [x + jitter(.03), .84]), taper(main));
      const loop = (t) => {
        const angle = Math.PI / 2 + t * Math.PI * 2, rx = .13, ry = .11 + .05 * Math.sin(angle / 2) ** 2;
        return [x + rx * Math.cos(angle), .25 + ry * Math.sin(angle)];
      };
      spine = loop;
    }
  }
  cell.stroke(spine, taper(main));
  const branches = 1 + Math.floor(r() * 3);
  for (let b = 0; b < branches; b++) {
    const at = [.28, .5, .72][Math.floor(r() * 3)] + jitter(.04), [x, y] = spine(Math.min(.95, Math.max(.05, at)));
    const side = r() < .5 ? -1 : 1, angle = (side < 0 ? Math.PI : 0) + jitter(.9), length = .16 + r() * .14;
    const end = [x + Math.cos(angle) * length, y + Math.sin(angle) * length];
    const kind = r();
    if (kind < .45) cell.stroke(line([x, y], end), taper(main * .72, .5));
    else if (kind < .8) {
      // A hook: out along the branch, then curling back.
      const curl = [end[0] + Math.cos(angle + side * 2.2) * .08, end[1] + Math.sin(angle + side * 2.2) * .08];
      cell.stroke(bezier([x, y], [x + (end[0] - x) * .6, y + (end[1] - y) * .6], end, curl), taper(main * .7, .45));
    } else {
      cell.stroke(line([x, y], end), taper(main * .65, .5));
      cell.dab(end[0] + Math.cos(angle) * .05, end[1] + Math.sin(angle) * .05, main * .75);
    }
  }
  const marks = Math.floor(r() * 2.4);
  for (let m = 0; m < marks; m++) {
    const kind = r(), mx = .22 + r() * .56, my = r() < .5 ? .1 + r() * .06 : .84 + r() * .05;
    if (kind < .4) cell.dab(mx, my, main * .7);
    else if (kind < .7) cell.stroke(arc(mx, my, .045, 0, Math.PI * 2), even(main * .38), 120);
    else if (kind < .85) cell.stroke(line([mx - .07, my], [mx + .07, my]), taper(main * .6, .5));
    else { // A small diamond.
      const d = .05;
      const corners = [[mx, my - d], [mx + d, my], [mx, my + d], [mx - d, my], [mx, my - d]];
      cell.stroke(t => { const k = Math.min(3, Math.floor(t * 4)); return line(corners[k], corners[k + 1])(t * 4 - k); }, even(main * .4), 200);
    }
  }
  // A crossbar through the body now and then.
  if (r() < .3) { const [x, y] = spine(.5 + jitter(.15)); cell.stroke(line([x - .12, y + jitter(.04)], [x + .12, y + jitter(.04)]), taper(main * .6, .5)); }
  return cell.finish();
}

/** The ornaments laid round the rune sphere and the seals: concentric rings, a toothed ring, a petal curl and a bead. */
function ornament(name) {
  const cell = new Cell();
  switch (name) {
    case "rings":
      for (const radius of [.37, .26, .15]) cell.stroke(arc(.5, .5, radius, 0, Math.PI * 2), even(.028), 360);
      cell.dab(.5, .5, .045);
      for (let k = 0; k < 12; k++) {
        const a = k * Math.PI / 6;
        cell.stroke(line([.5 + Math.cos(a) * .41, .5 + Math.sin(a) * .41], [.5 + Math.cos(a) * .46, .5 + Math.sin(a) * .46]), even(.022), 20);
      }
      break;
    case "gear":
      cell.stroke(arc(.5, .5, .3, 0, Math.PI * 2), even(.034), 360);
      cell.stroke(arc(.5, .5, .15, 0, Math.PI * 2), even(.026), 240);
      for (let k = 0; k < 14; k++) {
        const a = k * Math.PI * 2 / 14;
        for (const offset of [-.07, 0, .07]) {
          const b = a + offset * .5;
          cell.stroke(line([.5 + Math.cos(b) * .31, .5 + Math.sin(b) * .31], [.5 + Math.cos(b) * .41, .5 + Math.sin(b) * .41]), even(.03), 24);
        }
      }
      break;
    case "curl": {
      // A spiral winding out from the middle into a leaf.
      const turns = Math.PI * 3.2;
      cell.stroke(t => { const a = t * turns, radius = .03 + .26 * t; return [.47 + radius * Math.cos(a), .5 + radius * Math.sin(a)]; }, t => .02 + .04 * t, 420);
      const tip = [.47 + .29 * Math.cos(turns), .5 + .29 * Math.sin(turns)], out = [tip[0] + .14, tip[1] - .2];
      cell.stroke(bezier(tip, [tip[0] + .16, tip[1] + .02], [out[0] + .05, out[1] + .1], out), taper(.05, .3));
      cell.stroke(bezier(tip, [tip[0] - .02, tip[1] - .16], [out[0] - .12, out[1] + .02], out), taper(.045, .3));
      break;
    }
    default: // bead
      cell.stroke(arc(.5, .5, .27, 0, Math.PI * 2), even(.04), 300);
      cell.dab(.5, .5, .075);
      cell.stroke(arc(.5, .5, .41, 0, Math.PI * 2), even(.016), 360);
  }
  return cell.finish();
}

// --- the atlas -------------------------------------------------------------------------------------

const cells = [...Array.from({ length: GLYPHS }, (_, index) => glyph(index)), ...ORNAMENTS.map(ornament)];
const width = CELL * COLUMNS, height = CELL * ROWS, raw = Buffer.alloc((width * 4 + 1) * height);
cells.forEach((alpha, index) => {
  const ox = (index % COLUMNS) * CELL, oy = Math.floor(index / COLUMNS) * CELL;
  for (let y = 0; y < CELL; y++) for (let x = 0; x < CELL; x++) {
    const o = (oy + y) * (width * 4 + 1) + 1 + (ox + x) * 4;
    raw[o] = raw[o + 1] = raw[o + 2] = 255;
    raw[o + 3] = Math.round(Math.min(1, alpha[y * CELL + x]) * 255);
  }
});
mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, png(width, height, raw, 6));
console.log(`Wrote ${GLYPHS} runes and ${ORNAMENTS.length} ornaments to ${OUT}`);

if (process.argv.includes("--preview")) {
  // Twice the size, gold on a dark blue ground, a hairline round each cell.
  const scale = 2, pw = width * scale, ph = height * scale, sheet = Buffer.alloc((pw * 3 + 1) * ph);
  for (let y = 0; y < ph; y++) for (let x = 0; x < pw; x++) {
    const sx = Math.floor(x / scale), sy = Math.floor(y / scale), a = raw[sy * (width * 4 + 1) + 1 + sx * 4 + 3] / 255;
    const edge = sx % CELL === 0 || sy % CELL === 0 ? 30 : 0, o = y * (pw * 3 + 1) + 1 + x * 3;
    sheet[o] = Math.round(12 + edge + (255 - 12) * a); sheet[o + 1] = Math.round(18 + edge + (214 - 18) * a); sheet[o + 2] = Math.round(34 + edge + (122 - 34) * a);
  }
  mkdirSync(join(ROOT, "work"), { recursive: true });
  writeFileSync(join(ROOT, "work/rune-atlas.png"), png(pw, ph, sheet, 2));
  console.log("Preview: work/rune-atlas.png");
}

function png(w, h, data, colorType) {
  const table = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = bytes => { let c = ~0; for (const b of bytes) c = table[(c ^ b) & 255] ^ (c >>> 8); return (~c) >>> 0; };
  const chunk = (type, body) => {
    const length = Buffer.alloc(4); length.writeUInt32BE(body.length);
    const typed = Buffer.concat([Buffer.from(type), body]);
    const sum = Buffer.alloc(4); sum.writeUInt32BE(crc(typed));
    return Buffer.concat([length, typed, sum]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(w, 0); header.writeUInt32BE(h, 4); header[8] = 8; header[9] = colorType;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", header), chunk("IDAT", deflateSync(data)), chunk("IEND", Buffer.alloc(0))]);
}
