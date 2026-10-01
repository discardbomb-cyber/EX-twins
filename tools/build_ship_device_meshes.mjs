#!/usr/bin/env node
// The six ship shield device blocks (three families x generator and drone dock) as OBJ models,
// built after the author's references (work/ship-shield-refs): the RF emitter tower with folding
// masts, the gold-and-blue holocron cube, the marble hex sphere in its ring of galaxy orbs, the
// black charging base with three sliding modules, the rune plate with its floating ring and
// shards, and the steel lab sphere with cutouts round a plexus.
//
// Coordinates are block units (the block spans 0..1, y up, the front faces -Z). Each model is
// split into groups that ShipDeviceRenderer moves on its own: `body` (static, baked into the
// chunk as the block model), `ring` (the lit floor ring of a switched-on generator, in the block
// model's `_on` variant), `core`, `fx` and `shell_<i>` (drawn by the block entity renderer).
// Writes models/block/<id>.{obj,mtl}, the block and item model JSON, the part models the renderer
// loads, and the few textures the materials need (textures/block/ship/*.png).
//   node tools/build_ship_device_meshes.mjs
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";
import { compactObjText } from "./compact_obj.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const ASSETS = join(ROOT, "src/main/resources/assets/relics_addon");
const MODELS = join(ASSETS, "models/block");
const PARTS = join(ASSETS, "models/block/ship");
const ITEMS = join(ASSETS, "models/item");
const TEXTURES = join(ASSETS, "textures/block/ship");
const WHITE = "relics_addon:item/materials/rf_mesh_white";
const C = [.5, .5, .5];

// --- vector helpers ------------------------------------------------------------------------------
const add = (a, b) => a.map((v, i) => v + b[i]);
const sub = (a, b) => a.map((v, i) => v - b[i]);
const mul = (a, s) => a.map(v => v * s);
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const len = a => Math.hypot(...a);
const norm = a => { const l = len(a) || 1; return a.map(v => v / l); };
const sph = (theta, phi) => [Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi)];
/** Any unit vector perpendicular to a. */
const perp = a => norm(cross(a, Math.abs(a[1]) > .9 ? [1, 0, 0] : [0, 1, 0]));
/** Rotates p about the unit axis through the origin by angle (radians). */
function rotate(p, axis, angle) {
  const a = norm(axis), c = Math.cos(angle), s = Math.sin(angle);
  return add(add(mul(p, c), mul(cross(a, p), s)), mul(a, dot(a, p) * (1 - c)));
}
const rotateY = (p, angle, centre = C) => add(centre, rotate(sub(p, centre), [0, 1, 0], angle));

// --- mesh ----------------------------------------------------------------------------------------
class Mesh {
  constructor(name, materials) { this.name = name; this.materials = materials; this.v = []; this.n = []; this.t = []; this.groups = new Map(); }
  vert(p, n, uv = [.5, .5]) { this.v.push(p); this.n.push(norm(n)); this.t.push(uv); return this.v.length; }
  face(group, material, ...ids) {
    if (!this.materials[material]) throw new Error(`${this.name}: unknown material ${material}`);
    if (!this.groups.has(group)) this.groups.set(group, []);
    this.groups.get(group).push([material, ids]);
  }
  /** Flat convex polygon, double-sided unless `single`, flat-shaded by its Newell normal. */
  poly(group, material, points, uvs = null, single = false) {
    const n = norm(points.reduce((acc, p, i) => {
      const q = points[(i + 1) % points.length];
      return [acc[0] + (p[1] - q[1]) * (p[2] + q[2]), acc[1] + (p[2] - q[2]) * (p[0] + q[0]), acc[2] + (p[0] - q[0]) * (p[1] + q[1])];
    }, [0, 0, 0]));
    if (!Number.isFinite(n[0]) || len(n) < .5) return;
    const front = points.map((p, i) => this.vert(p, n, uvs?.[i]));
    for (let i = 1; i + 1 < front.length; i++) this.face(group, material, front[0], front[i], front[i + 1]);
    if (single) return;
    const back = points.map((p, i) => this.vert(p, mul(n, -1), uvs?.[i]));
    for (let i = 1; i + 1 < back.length; i++) this.face(group, material, back[0], back[i + 1], back[i]);
  }
  /** Smooth quad with per-corner normals (double-sided). */
  quad(group, material, points, normals, uvs = null) {
    const ids = points.map((p, i) => this.vert(p, normals[i], uvs?.[i]));
    this.face(group, material, ids[0], ids[1], ids[2]);
    this.face(group, material, ids[0], ids[2], ids[3]);
    const back = points.map((p, i) => this.vert(p, mul(normals[i], -1), uvs?.[i]));
    this.face(group, material, back[0], back[2], back[1]);
    this.face(group, material, back[0], back[3], back[2]);
  }
  /** Closed box: centre, three half-axis vectors (length = half size). Materials per axis pair or one. */
  box(group, material, centre, ax, ay, az) {
    const corner = (sx, sy, sz) => add(add(add(centre, mul(ax, sx)), mul(ay, sy)), mul(az, sz));
    const m = typeof material === "function" ? material : () => material;
    this.poly(group, m(0), [corner(1, -1, -1), corner(1, 1, -1), corner(1, 1, 1), corner(1, -1, 1)], null, true);
    this.poly(group, m(0), [corner(-1, -1, 1), corner(-1, 1, 1), corner(-1, 1, -1), corner(-1, -1, -1)], null, true);
    this.poly(group, m(1), [corner(-1, 1, -1), corner(-1, 1, 1), corner(1, 1, 1), corner(1, 1, -1)], null, true);
    this.poly(group, m(1), [corner(-1, -1, 1), corner(-1, -1, -1), corner(1, -1, -1), corner(1, -1, 1)], null, true);
    this.poly(group, m(2), [corner(-1, -1, 1), corner(1, -1, 1), corner(1, 1, 1), corner(-1, 1, 1)], null, true);
    this.poly(group, m(2), [corner(1, -1, -1), corner(-1, -1, -1), corner(-1, 1, -1), corner(1, 1, -1)], null, true);
  }
  /** Axis-aligned box from min to max corners. */
  aabb(group, material, min, max) {
    const h = mul(sub(max, min), .5), c = add(min, h);
    this.box(group, material, c, [h[0], 0, 0], [0, h[1], 0], [0, 0, h[2]]);
  }
  /** Surface of revolution: profile = [[radius, along]] from the centre along the axis; closed when the ends have radius 0. */
  lathe(group, material, centre, axis, profile, segments = 32, uvScale = null) {
    const a = norm(axis), u = perp(a), w = cross(a, u);
    const ring = ([radius, along], t) => add(add(centre, mul(a, along)), add(mul(u, Math.cos(t) * radius), mul(w, Math.sin(t) * radius)));
    for (let s = 0; s < segments; s++) {
      const t0 = 2 * Math.PI * s / segments, t1 = 2 * Math.PI * (s + 1) / segments;
      for (let i = 0; i + 1 < profile.length; i++) {
        const q = [ring(profile[i], t0), ring(profile[i], t1), ring(profile[i + 1], t1), ring(profile[i + 1], t0)];
        if (len(sub(q[0], q[3])) < 1e-6 && len(sub(q[1], q[2])) < 1e-6) continue;
        const uvs = uvScale ? [[s / segments * uvScale[0], i / (profile.length - 1) * uvScale[1]], [(s + 1) / segments * uvScale[0], i / (profile.length - 1) * uvScale[1]],
          [(s + 1) / segments * uvScale[0], (i + 1) / (profile.length - 1) * uvScale[1]], [s / segments * uvScale[0], (i + 1) / (profile.length - 1) * uvScale[1]]] : null;
        this.poly(group, typeof material === "function" ? material(i) : material, q, uvs);
      }
    }
  }
  /** Straight tube (cylinder) from a to b, closed. */
  tube(group, material, a, b, radius, segments = 8) {
    const d = sub(b, a);
    this.lathe(group, material, a, d, [[0, 0], [radius, 0], [radius, len(d)], [0, len(d)]], segments);
  }
  /** Rectangular-section ribbon along a polyline; side = the ribbon's width direction at each point. */
  ribbon(group, path, side, width, depth, faceMaterial, edgeMaterial = faceMaterial, closed = false) {
    const rings = path.map((p, i) => {
      const s = norm(side[i]);
      const prev = path[closed ? (i - 1 + path.length) % path.length : Math.max(i - 1, 0)];
      const next = path[closed ? (i + 1) % path.length : Math.min(i + 1, path.length - 1)];
      const out = norm(cross(s, sub(next, prev)));
      const h = mul(s, width / 2), d = mul(out, depth / 2);
      return [add(add(p, h), d), add(sub(p, h), d), sub(sub(p, h), d), sub(add(p, h), d)];
    });
    const count = closed ? rings.length : rings.length - 1;
    for (let i = 0; i < count; i++) {
      const a = rings[i], b = rings[(i + 1) % rings.length];
      for (let k = 0; k < 4; k++) {
        const k2 = (k + 1) % 4;
        this.poly(group, k === 0 || k === 2 ? faceMaterial : edgeMaterial, [a[k], b[k], b[k2], a[k2]], null, true);
      }
    }
    if (!closed) {
      this.poly(group, edgeMaterial, [...rings[0]].reverse(), null, true);
      this.poly(group, edgeMaterial, rings[rings.length - 1], null, true);
    }
  }
  /** Flat annulus (or sector) in the plane with normal `axis`, between two radii. */
  annulus(group, material, centre, axis, inner, outer, segments = 32, from = 0, to = 2 * Math.PI) {
    const a = norm(axis), u = perp(a), w = cross(a, u);
    const at = (r, t) => add(centre, add(mul(u, Math.cos(t) * r), mul(w, Math.sin(t) * r)));
    for (let s = 0; s < segments; s++) {
      const t0 = from + (to - from) * s / segments, t1 = from + (to - from) * (s + 1) / segments;
      this.poly(group, material, [at(inner, t0), at(inner, t1), at(outer, t1), at(outer, t0)]);
    }
  }
  /** Extruded regular or custom polygon: 2D points [[x, z]] round `centre`, from y0 to y1. */
  prism(group, material, centre, points2d, y0, y1, sideMaterial = material) {
    const at = (p, y) => [centre[0] + p[0], y, centre[2] + p[1]];
    this.poly(group, material, points2d.map(p => at(p, y1)), null, true);
    this.poly(group, material, [...points2d].reverse().map(p => at(p, y0)), null, true);
    for (let i = 0; i < points2d.length; i++) {
      const p = points2d[i], q = points2d[(i + 1) % points2d.length];
      this.poly(group, sideMaterial, [at(p, y0), at(q, y0), at(q, y1), at(p, y1)], null, true);
    }
  }
  /** UV sphere: material(theta, phi, dir) picks the material per quad, or null to leave a hole. */
  sphere(group, material, centre, radius, rows, cols, uvScale = [1, 1]) {
    for (let row = 0; row < rows; row++) for (let col = 0; col < cols; col++) {
      const t0 = Math.PI * row / rows, t1 = Math.PI * (row + 1) / rows;
      const p0 = 2 * Math.PI * col / cols, p1 = 2 * Math.PI * (col + 1) / cols;
      const dirs = [sph(t0, p0), sph(t0, p1), sph(t1, p1), sph(t1, p0)];
      const m = typeof material === "function" ? material((t0 + t1) / 2, (p0 + p1) / 2, sph((t0 + t1) / 2, (p0 + p1) / 2)) : material;
      if (!m) continue;
      const uvs = [[col / cols, row / rows], [(col + 1) / cols, row / rows], [(col + 1) / cols, (row + 1) / rows], [col / cols, (row + 1) / rows]]
        .map(([u, v]) => [u * uvScale[0], v * uvScale[1]]);
      this.quad(group, m, dirs.map(d => add(centre, mul(d, radius))), dirs, uvs);
    }
  }
  write(order) {
    const lines = [`# Generated by tools/build_ship_device_meshes.mjs; block units, front faces -Z`, `mtllib ${this.name}.mtl`];
    for (const p of this.v) lines.push(`v ${p.map(x => x.toFixed(6)).join(" ")}`);
    for (const t of this.t) lines.push(`vt ${t.map(x => x.toFixed(5)).join(" ")}`);
    for (const n of this.n) lines.push(`vn ${n.map(x => x.toFixed(6)).join(" ")}`);
    for (const group of order) {
      if (!this.groups.has(group)) throw new Error(`${this.name}: group ${group} is empty`);
      lines.push(`g ${group}`);
      let current = null;
      for (const [material, ids] of this.groups.get(group)) {
        if (material !== current) { lines.push(`usemtl ${material}`); current = material; }
        lines.push(`f ${ids.map(i => `${i}/${i}/${i}`).join(" ")}`);
      }
    }
    for (const group of this.groups.keys()) if (!order.includes(group)) throw new Error(`${this.name}: group ${group} not in the part order`);
    mkdirSync(MODELS, { recursive: true });
    writeFileSync(join(MODELS, `${this.name}.obj`), compactObjText(lines.join("\n") + "\n").text);
    const mtl = Object.entries(this.materials).map(([id, [kd, ka, map]]) =>
      `newmtl ${id}\nKd ${kd.join(" ")}\nKa ${ka} ${ka} ${ka}\nmap_Kd ${map ?? WHITE}\n`);
    writeFileSync(join(MODELS, `${this.name}.mtl`), mtl.join("\n"));
    const faces = [...this.groups.values()].reduce((sum, list) => sum + list.length, 0);
    console.log(`${this.name}.obj: ${this.v.length} vertices, ${faces} triangles, groups ${order.join(" ")}`);
  }
}

// --- textures ------------------------------------------------------------------------------------
function png(width, height, pixel) {
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    for (let x = 0; x < width; x++) {
      const c = pixel(x, y);
      raw.set([c[0], c[1], c[2], c[3] ?? 255].map(v => Math.max(0, Math.min(255, Math.round(v)))), y * (width * 4 + 1) + 1 + x * 4);
    }
  }
  const table = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
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
const hash = (x, y, seed) => { let h = (x * 374761393 + y * 668265263 + seed * 1274126177) | 0; h = (h ^ (h >>> 13)) * 1274126177 | 0; return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };
/** Tileable value noise. */
function noise(x, y, size, cell, seed) {
  const gx = Math.floor(x / cell), gy = Math.floor(y / cell), fx = x / cell - gx, fy = y / cell - gy, n = size / cell;
  const s = t => t * t * (3 - 2 * t), g = (i, j) => hash(((i % n) + n) % n, ((j % n) + n) % n, seed);
  const top = g(gx, gy) + (g(gx + 1, gy) - g(gx, gy)) * s(fx), bottom = g(gx, gy + 1) + (g(gx + 1, gy + 1) - g(gx, gy + 1)) * s(fx);
  return top + (bottom - top) * s(fy);
}
const fbm = (x, y, size, seed) => (noise(x, y, size, 16, seed) * .5 + noise(x, y, size, 8, seed + 1) * .3 + noise(x, y, size, 4, seed + 2) * .2);

function writeTextures() {
  mkdirSync(TEXTURES, { recursive: true });
  const S = 64;
  // White marble with grey veins (the Ex-Twins sphere and pillars).
  writeFileSync(join(TEXTURES, "marble.png"), png(S, S, (x, y) => {
    const vein = Math.abs(Math.sin((x + y * .6) / 5 + fbm(x, y, S, 3) * 9));
    const base = 232 - fbm(x, y, S, 7) * 22;
    const v = vein < .12 ? 1 - vein / .12 : 0;
    const c = base - v * 70;
    return [c, c + 2, c + 6];
  }));
  // Deep violet galaxy with stars (the orbs of the Ex-Twins ring).
  writeFileSync(join(TEXTURES, "galaxy.png"), png(32, 32, (x, y) => {
    const n = fbm(x * 2, y * 2, S, 11), swirl = Math.pow(Math.max(0, Math.sin((x - 16) * .35 + (y - 16) * .2 + n * 6)), 3);
    const star = hash(x, y, 5) > .93 ? 180 : 0;
    return [26 + n * 60 + swirl * 120 + star, 10 + n * 30 + swirl * 50 + star, 48 + n * 90 + swirl * 150 + star];
  }));
  // Brushed steel (the lab sphere and the RF tower).
  writeFileSync(join(TEXTURES, "steel.png"), png(32, 32, (x, y) => {
    const c = 142 + (hash(0, y, 9) - .5) * 24 + (noise(x, y, 32, 8, 21) - .5) * 20;
    return [c, c + 3, c + 7];
  }));
  // Black panel plastic with faint seams (the RF dock).
  writeFileSync(join(TEXTURES, "panel.png"), png(32, 32, (x, y) => {
    const seam = x % 16 === 0 || y % 16 === 0 ? -8 : 0;
    const c = 34 + (noise(x, y, 32, 4, 31) - .5) * 10 + seam;
    return [c, c + 1, c + 3];
  }));
  // Dark blue stone with carved lines (the Mana rune plate).
  writeFileSync(join(TEXTURES, "runestone.png"), png(32, 32, (x, y) => {
    const n = fbm(x * 2, y * 2, S, 41);
    const c = 38 + n * 30;
    return [c, c + 10, c + 26];
  }));
}

// --- the RF emitter tower (s11, s10) -------------------------------------------------------------
function rfGenerator() {
  const m = new Mesh("rf_ship_shield_generator", {
    steel: [[.62, .66, .7], 0, "relics_addon:block/ship/steel"],
    steel_dark: [[.3, .33, .37], 0],
    steel_light: [[.8, .83, .86], 0],
    graphite: [[.1, .11, .13], 0],
    rubber: [[.05, .055, .065], 0],
    cyan: [[.3, .82, 1], 1],
    cyan_dim: [[.15, .45, .6], .3],
    ring_light: [[.72, .96, 1], 1],
  });
  const y0 = 0;
  // Octagonal base plate with a raised rim and four foot pads.
  const oct = r => Array.from({ length: 8 }, (_, i) => [Math.cos((i + .5) * Math.PI / 4) * r, Math.sin((i + .5) * Math.PI / 4) * r]);
  m.prism("body", "steel_dark", C, oct(.46), y0, y0 + .06, "graphite");
  m.prism("body", "steel", C, oct(.36), y0 + .06, y0 + .1, "steel_dark");
  for (let i = 0; i < 4; i++) {
    const a = i * Math.PI / 2 + Math.PI / 4, d = [Math.cos(a), 0, Math.sin(a)], t = [-Math.sin(a), 0, Math.cos(a)];
    m.box("body", k => (k === 1 ? "steel_light" : "steel_dark"), add([.5, y0 + .085, .5], mul(d, .38)), mul(d, .07), [0, .035, 0], mul(t, .05));
    m.box("body", "cyan_dim", add([.5, y0 + .12, .5], mul(d, .4)), mul(d, .02), [0, .006, 0], mul(t, .03));
  }
  // Drum and the segmented column.
  m.lathe("body", i => (i === 1 ? "graphite" : i >= 4 ? "steel_dark" : "steel"), [.5, y0 + .1, .5], [0, 1, 0],
    [[0, 0], [.24, 0], [.24, .05], [.2, .05], [.2, .14], [.26, .17], [.26, .2], [.17, .22], [0, .22]], 24);
  m.lathe("body", i => (i % 2 ? "steel_dark" : "steel"), [.5, y0 + .32, .5], [0, 1, 0],
    [[.13, 0], [.14, .08], [.11, .1], [.11, .18], [.14, .2], [.14, .3], [.11, .32], [.11, .44], [.15, .46], [.15, .55], [.17, .6], [0, .6]], 20);
  // Hinge collar for the masts near the column's top.
  m.lathe("body", "graphite", [.5, y0 + .78, .5], [0, 1, 0], [[.12, 0], [.19, 0], [.19, .06], [.12, .06]], 16);
  // Lamps on the drum: fx (drawn fullbright on the switched-on tower, blinking at rest).
  for (let i = 0; i < 4; i++) {
    const a = i * Math.PI / 2, d = [Math.cos(a), 0, Math.sin(a)], t = [-Math.sin(a), 0, Math.cos(a)];
    m.box("fx", "cyan", add([.5, y0 + .27, .5], mul(d, .255)), mul(d, .008), [0, .012, 0], mul(t, .03));
  }
  // Core: the antenna spindle with its three vanes and the top lamp; spins slowly.
  m.lathe("core", i => (i >= 3 ? "steel_light" : "steel_dark"), [.5, y0 + .92, .5], [0, 1, 0], [[0, 0], [.05, 0], [.05, .1], [.03, .1], [.03, .32], [.045, .34], [0, .38]], 12);
  for (let i = 0; i < 3; i++) {
    const a = i * 2 * Math.PI / 3, d = [Math.cos(a), 0, Math.sin(a)], t = [-Math.sin(a), 0, Math.cos(a)];
    m.box("core", "steel_light", add([.5, y0 + 1.16, .5], mul(d, .07)), mul(d, .045), [0, .055, 0], mul(t, .005));
  }
  m.box("fx", "cyan", [.5, y0 + 1.31, .5], [.018, 0, 0], [0, .018, 0], [0, 0, .018]);
  // Masts: four lattice arms hinged at the collar, modelled folded down along the column. The
  // renderer swings each about its tangent axis through the pivot to raise it.
  for (let i = 0; i < 4; i++) {
    const a = i * Math.PI / 2 + Math.PI / 4, d = [Math.cos(a), 0, Math.sin(a)], t = [-Math.sin(a), 0, Math.cos(a)];
    const pivot = add([.5, y0 + .81, .5], mul(d, .21));
    const group = `shell_${i}`;
    m.lathe(group, "steel_dark", add(pivot, mul(t, -.04)), t, [[0, 0], [.03, 0], [.03, .08], [0, .08]], 10);
    const length = .58;
    for (const s of [-1, 1]) {
      m.box(group, "steel", add(pivot, add(mul(t, s * .022), [0, -length / 2, 0])), mul(t, .007), [0, length / 2, 0], mul(d, .007));
    }
    for (let k = 0; k < 5; k++) m.box(group, "steel_light", add(pivot, [0, -.08 - k * .095, 0]), mul(t, .022), [0, .005, 0], mul(d, .005));
    // Panel at the free end, and its lamp.
    const end = add(pivot, [0, -length + .04, 0]);
    m.box(group, k => (k === 0 ? "steel_light" : "graphite"), add(end, mul(d, .022)), mul(d, .008), [0, .09, 0], mul(t, .075));
    m.box(group, "cyan", add(end, mul(d, .032)), mul(d, .003), [0, .012, 0], mul(t, .012));
  }
  // The lit floor ring (s10) in the block's `_on` model.
  m.annulus("ring", "ring_light", [.5, y0 + .062, .5], [0, 1, 0], .4, .45, 32);
  m.write(["body", "ring", "core", "fx", "shell_0", "shell_1", "shell_2", "shell_3"]);
  return { shells: 4, height: 1.35 };
}

// --- the Mana holocron cube (s05, s10) -----------------------------------------------------------
function manaGenerator() {
  const m = new Mesh("mana_ship_shield_generator", {
    gold: [[.6, .42, .14], 0],
    gold_edge: [[.92, .74, .32], .1],
    bronze: [[.35, .24, .1], 0],
    navy: [[.03, .06, .14], 0],
    glass: [[.1, .35, .85], .25],
    glass_light: [[.45, .8, 1], .9],
    core_light: [[1, .85, .45], 1],
    ring_light: [[.8, .95, 1], 1],
  });
  const oct = r => Array.from({ length: 8 }, (_, i) => [Math.cos((i + .5) * Math.PI / 4) * r, Math.sin((i + .5) * Math.PI / 4) * r]);
  m.prism("body", "bronze", C, oct(.44), 0, .05, "gold");
  m.prism("body", "navy", C, oct(.34), .05, .1, "gold_edge");
  m.lathe("body", i => (i === 0 ? "gold_edge" : "gold"), [.5, .1, .5], [0, 1, 0], [[.2, 0], [.2, .03], [.14, .05], [.1, .12], [0, .12]], 16);
  for (let i = 0; i < 4; i++) {
    const a = i * Math.PI / 2, d = [Math.cos(a), 0, Math.sin(a)], t = [-Math.sin(a), 0, Math.cos(a)];
    m.box("body", "gold_edge", add([.5, .11, .5], mul(d, .3)), mul(d, .06), [0, .012, 0], mul(t, .02));
    m.box("fx", "glass_light", add([.5, .105, .5], mul(d, .25)), mul(d, .015), [0, .01, 0], mul(t, .012));
  }
  m.annulus("ring", "ring_light", [.5, .052, .5], [0, 1, 0], .39, .43, 32);
  // The cube floats above the plinth. Six face plates (shells): a gold frame round a blue lens
  // whose glass dishes inward, and a gold pyramid behind it that only shows when the star opens.
  const centre = [.5, .6, .5], half = .2;
  const faces = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
  faces.forEach((n, i) => {
    const group = `shell_${i}`, u = perp(n), w = cross(n, u);
    const at = (x, y, out) => add(add(add(centre, mul(n, half + out)), mul(u, x)), mul(w, y));
    // Frame: four gold bars with a bevelled outer edge.
    const bar = (sx, sy, cx, cy) => m.box(group, k => (k === 2 ? "gold_edge" : "gold"), at(cx, cy, -.025), mul(u, sx), mul(w, sy), mul(n, .025));
    bar(half, .04, 0, half - .04); bar(half, .04, 0, -half + .04); bar(.04, half - .08, half - .04, 0); bar(.04, half - .08, -half + .04, 0);
    // Lens: a square glass plate set back, with a glowing diamond on it.
    m.poly(group, "glass", [at(-.16, -.16, -.03), at(.16, -.16, -.03), at(.16, .16, -.03), at(-.16, .16, -.03)]);
    m.poly(group, "navy", [at(-.165, -.165, -.042), at(.165, -.165, -.042), at(.165, .165, -.042), at(-.165, .165, -.042)]);
    m.poly(group, "glass_light", [at(0, .1, -.028), at(-.1, 0, -.028), at(0, -.1, -.028), at(.1, 0, -.028)]);
    m.poly(group, "glass", [at(0, .06, -.026), at(-.06, 0, -.026), at(0, -.06, -.026), at(.06, 0, -.026)]);
  });
  // Core: a star, a blue octahedron lit from within with a gold pyramid on each face (the open
  // holocron's points), hidden inside the closed cube.
  const r = .11;
  const tips = [[r, 0, 0], [-r, 0, 0], [0, r, 0], [0, -r, 0], [0, 0, r], [0, 0, -r]].map(d => add(centre, d));
  for (const [a, b, c] of [[0, 2, 4], [4, 2, 1], [1, 2, 5], [5, 2, 0], [4, 3, 0], [1, 3, 4], [5, 3, 1], [0, 3, 5]]) {
    const tri = [tips[a], tips[b], tips[c]], n = norm(sub(mul(add(add(tri[0], tri[1]), tri[2]), 1 / 3), centre));
    const apex = add(centre, mul(n, .19));
    for (let k = 0; k < 3; k++) m.poly("core", k === 0 ? "gold_edge" : "gold", [tri[k], tri[(k + 1) % 3], apex], null, true);
    m.poly("core", "core_light", tri.map(p => add(p, mul(n, .004))), null, true);
  }
  for (const [a, b] of [[0, 2], [2, 1], [1, 3], [3, 0], [4, 2], [2, 5], [5, 3], [3, 4], [0, 4], [4, 1], [1, 5], [5, 0]]) m.tube("core", "glass_light", tips[a], tips[b], .008, 5);
  m.write(["body", "ring", "core", "fx", "shell_0", "shell_1", "shell_2", "shell_3", "shell_4", "shell_5"]);
  return { shells: 6, height: 1 };
}

// --- the Ex-Twins marble hex sphere (s14, s15) ---------------------------------------------------
/** Truncated icosahedron: 60 vertices, 12 pentagons and 20 hexagons, scaled to the unit sphere. */
function truncatedIcosahedron() {
  const phi = (1 + Math.sqrt(5)) / 2;
  const base = [];
  const perms = (a, b, c) => [[a, b, c], [b, c, a], [c, a, b]];
  for (const [x, y, z] of [[0, 1, 3 * phi], [1, 2 + phi, 2 * phi], [phi, 2, 2 * phi + 1]]) {
    for (const p of perms(x, y, z)) for (const sx of [1, -1]) for (const sy of [1, -1]) for (const sz of [1, -1]) {
      const v = [p[0] * sx, p[1] * sy, p[2] * sz];
      if (!base.some(q => len(sub(q, v)) < 1e-9)) base.push(v);
    }
  }
  const verts = base.map(norm);
  const edges = [];
  for (let i = 0; i < verts.length; i++) for (let j = i + 1; j < verts.length; j++) if (len(sub(base[i], base[j])) < 2.0001) edges.push([i, j]);
  // Faces: the vertex ring round each face normal (hexagon centres = icosahedron faces, pentagon centres = its vertices).
  const faces = [];
  const centres = [];
  for (const [x, y, z] of [[0, 1, phi]]) for (const p of perms(x, y, z)) for (const sy of [1, -1]) for (const sz of [1, -1]) centres.push({ n: norm([p[0], p[1] * sy, p[2] * sz]), size: 5 });
  for (const s of [[1, 1, 1], [1, 1, -1], [1, -1, 1], [1, -1, -1], [-1, 1, 1], [-1, 1, -1], [-1, -1, 1], [-1, -1, -1]]) centres.push({ n: norm(s), size: 6 });
  for (const [x, y, z] of [[0, 1 / phi, phi]]) for (const p of perms(x, y, z)) for (const sy of [1, -1]) for (const sz of [1, -1]) centres.push({ n: norm([p[0], p[1] * sy, p[2] * sz]), size: 6 });
  for (const { n, size } of centres) {
    const ring = verts.map((v, i) => ({ i, d: dot(v, n) })).sort((a, b) => b.d - a.d).slice(0, size).map(e => e.i);
    const u = perp(n), w = cross(n, u);
    ring.sort((a, b) => Math.atan2(dot(verts[a], w), dot(verts[a], u)) - Math.atan2(dot(verts[b], w), dot(verts[b], u)));
    faces.push({ n, ring });
  }
  return { verts, edges, faces };
}

function twinsGenerator() {
  const m = new Mesh("twins_ship_shield_generator", {
    marble: [[.95, .95, .96], 0, "relics_addon:block/ship/marble"],
    marble_dark: [[.6, .6, .64], 0, "relics_addon:block/ship/marble"],
    gold: [[.72, .52, .18], .05],
    gold_edge: [[.95, .78, .38], .15],
    galaxy: [[1, 1, 1], .35, "relics_addon:block/ship/galaxy"],
    thread: [[1, .85, .5], 1],
    ring_light: [[.95, .85, 1], 1],
  });
  // Pedestal: a marble drum with gold hoops and four marble clamps (s15 pillars), on an octagonal plinth.
  const oct = r => Array.from({ length: 8 }, (_, i) => [Math.cos((i + .5) * Math.PI / 4) * r, Math.sin((i + .5) * Math.PI / 4) * r]);
  m.prism("body", "marble_dark", C, oct(.44), 0, .05, "marble");
  m.lathe("body", i => (i === 1 || i === 4 ? "gold_edge" : i >= 6 ? "gold" : "marble"), [.5, .05, .5], [0, 1, 0],
    [[.22, 0], [.22, .05], [.24, .07], [.21, .07], [.21, .16], [.24, .18], [.2, .18], [.1, .18], [.1, .26], [0, .26]], 24, [4, 1]);
  for (let i = 0; i < 4; i++) {
    const a = i * Math.PI / 2 + Math.PI / 4, d = [Math.cos(a), 0, Math.sin(a)], t = [-Math.sin(a), 0, Math.cos(a)];
    m.box("body", k => (k === 1 ? "gold" : "marble"), add([.5, .2, .5], mul(d, .2)), mul(d, .04), [0, .08, 0], mul(t, .05));
  }
  m.annulus("ring", "ring_light", [.5, .052, .5], [0, 1, 0], .39, .43, 32);
  // The sphere: marble hex and pentagon plates with gold seams, plus a slightly smaller smooth marble
  // sphere behind them so no seam shows through.
  const centre = [.5, .62, .5], R = .3;
  const { verts, edges, faces } = truncatedIcosahedron();
  for (const { n, ring } of faces) {
    const points = ring.map(i => add(centre, mul(verts[i], R)));
    const u = perp(n), w = cross(n, u);
    const uvs = points.map(p => { const q = sub(p, centre); return [.5 + dot(q, u) * 1.4, .5 + dot(q, w) * 1.4]; });
    m.poly("body", "marble", points, uvs, true);
  }
  m.sphere("body", "marble_dark", centre, R * .88, 8, 14, [4, 2]);
  for (const [a, b] of edges) m.tube("body", "gold_edge", add(centre, mul(verts[a], R * 1.01)), add(centre, mul(verts[b], R * 1.01)), .011, 5);
  // Core: the ring of galaxy orbs on a thin gold rail, tilted; it spins when the generator runs.
  const tilt = .38, axis = norm([Math.sin(tilt), Math.cos(tilt), 0]), ringR = .54, orbs = 14;
  const u = perp(axis), w = cross(axis, u);
  const onRing = t => add(centre, add(mul(u, Math.cos(t) * ringR), mul(w, Math.sin(t) * ringR)));
  const rail = [], side = [];
  for (let s = 0; s < 48; s++) { rail.push(onRing(2 * Math.PI * s / 48)); side.push(axis); }
  m.ribbon("core", rail, side, .012, .008, "gold", "gold_edge", true);
  for (let i = 0; i < orbs; i++) {
    const p = onRing(2 * Math.PI * i / orbs);
    m.sphere("core", "galaxy", p, .055, 5, 8);
    m.lathe("core", "gold_edge", p, axis, [[.058, -.006], [.062, 0], [.058, .006]], 10);
  }
  // Fx: golden threads from the pedestal to the sphere and on to the ring, slightly twisted.
  for (let i = 0; i < 6; i++) {
    const a = i * Math.PI / 3, path = [], sideT = [];
    for (let s = 0; s <= 12; s++) {
      const f = s / 12, y = .3 + f * (centre[1] - R - .3 + .04);
      const r = .16 - f * .1 + Math.sin(f * Math.PI) * .06, b = a + f * .9;
      path.push([.5 + Math.cos(b) * r, y, .5 + Math.sin(b) * r]);
      sideT.push([0, 1, 0]);
    }
    m.ribbon("fx", path, sideT, .006, .006, "thread");
  }
  for (let i = 0; i < 4; i++) {
    const a = i * Math.PI / 2 + .3, path = [], sideT = [];
    const end = onRing(a);
    for (let s = 0; s <= 10; s++) {
      const f = s / 10, p = add(mul(add(centre, mul(norm(sub(end, centre)), R)), 1 - f), mul(end, f));
      path.push(add(p, mul(axis, Math.sin(f * Math.PI) * .05)));
      sideT.push(axis);
    }
    m.ribbon("fx", path, sideT, .006, .006, "thread");
  }
  m.write(["body", "ring", "core", "fx"]);
  return { shells: 0, height: 1.1 };
}

// --- the RF charging base (s06, s07) -------------------------------------------------------------
function rfDock() {
  const m = new Mesh("rf_drone_dock", {
    panel: [[.42, .43, .46], 0, "relics_addon:block/ship/panel"],
    black: [[.08, .085, .1], 0],
    grey: [[.3, .32, .36], 0],
    light_grey: [[.5, .53, .58], 0],
    cyan: [[.25, .85, 1], 1],
    cyan_dim: [[.12, .4, .55], .25],
    gold: [[.9, .72, .3], .1],
  });
  // Base disc with a stepped rim, a ring of spoke slots and the raised hub.
  m.lathe("body", i => (i <= 1 ? "black" : i === 3 ? "panel" : "grey"), [.5, 0, .5], [0, 1, 0],
    [[0, 0], [.5, 0], [.5, .07], [.47, .09], [.33, .1], [.3, .13], [.22, .13], [.2, .2], [.17, .22], [0, .22]], 36, [6, 1]);
  for (let i = 0; i < 12; i++) {
    const a = i * Math.PI / 6 + Math.PI / 12, d = [Math.cos(a), 0, Math.sin(a)], t = [-Math.sin(a), 0, Math.cos(a)];
    m.box("body", "black", add([.5, .1, .5], mul(d, .4)), mul(d, .05), [0, .012, 0], mul(t, .012));
  }
  // Hub top plate with four gold contact pins and a dark well round them.
  m.lathe("body", "black", [.5, .22, .5], [0, 1, 0], [[.09, 0], [.09, .008], [0, .008]], 16);
  for (const [x, z] of [[-.03, 0], [-.01, 0], [.01, 0], [.03, 0]]) m.aabb("body", "gold", [.5 + x - .006, .225, .5 + z - .006], [.5 + x + .006, .238, .5 + z + .006]);
  // Concentric cyan rings on the base round the hub, broken into arcs: fx, brighter with drones aboard.
  for (const [r, gaps] of [[.25, 3], [.29, 4], [.34, 2]]) {
    for (let g = 0; g < gaps; g++) {
      const from = g * 2 * Math.PI / gaps + .25, to = (g + 1) * 2 * Math.PI / gaps - .25;
      m.annulus("fx", "cyan", [.5, .102, .5], [0, 1, 0], r - .012, r + .012, 12, from, to);
    }
  }
  m.annulus("fx", "cyan_dim", [.5, .101, .5], [0, 1, 0], .2, .215, 24);
  // Three modules on the base that slide outward when a drone sits in the dock.
  for (let i = 0; i < 3; i++) {
    const a = i * 2 * Math.PI / 3 + Math.PI / 2, d = [Math.cos(a), 0, Math.sin(a)], t = [-Math.sin(a), 0, Math.cos(a)];
    const group = `shell_${i}`, c = add([.5, .145, .5], mul(d, .34));
    m.box(group, k => (k === 1 ? "panel" : "black"), c, mul(d, .16), [0, .045, 0], mul(t, .1));
    m.box(group, "grey", add(c, [0, .045, 0]), mul(d, .12), [0, .006, 0], mul(t, .07));
    m.box(group, "light_grey", add(c, add(mul(d, .1), [0, .02, 0])), mul(d, .06), [0, .035, 0], mul(t, .11));
    for (let k = -1; k <= 1; k++) m.box(group, "cyan", add(c, add(mul(d, -.06), add([0, .052, 0], mul(t, k * .035)))), mul(d, .012), [0, .004, 0], mul(t, .006));
    m.box(group, "black", add(c, add(mul(d, .17), [0, -.01, 0])), mul(d, .012), [0, .02, 0], mul(t, .04));
  }
  // Core: the hub's inner light column seen through the top well.
  m.lathe("core", "cyan", [.5, .2, .5], [0, 1, 0], [[.12, 0], [.12, .012], [.1, .012]], 16);
  m.write(["body", "core", "fx", "shell_0", "shell_1", "shell_2"]);
  return { shells: 3, height: .3 };
}

// --- the Mana rune dock (s13) ----------------------------------------------------------------------
function manaDock() {
  const m = new Mesh("mana_drone_dock", {
    stone: [[.75, .8, .9], 0, "relics_addon:block/ship/runestone"],
    stone_dark: [[.4, .45, .55], 0, "relics_addon:block/ship/runestone"],
    slate: [[.12, .16, .24], 0],
    steel: [[.35, .42, .5], 0],
    teal: [[.3, .95, 1], 1],
    teal_dim: [[.15, .5, .6], .3],
    core_light: [[.8, 1, 1], 1],
  });
  // The rune plate: a low octagonal stone slab, carved lines glowing on top.
  const oct = r => Array.from({ length: 8 }, (_, i) => [Math.cos((i + .5) * Math.PI / 4) * r, Math.sin((i + .5) * Math.PI / 4) * r]);
  m.prism("body", "stone_dark", C, oct(.47), 0, .05, "slate");
  m.prism("body", "stone", C, oct(.42), .05, .08, "stone_dark");
  m.annulus("fx", "teal", [.5, .082, .5], [0, 1, 0], .3, .315, 24);
  m.annulus("fx", "teal_dim", [.5, .082, .5], [0, 1, 0], .36, .37, 32);
  for (let i = 0; i < 8; i++) {
    const a = i * Math.PI / 4, d = [Math.cos(a), 0, Math.sin(a)], t = [-Math.sin(a), 0, Math.cos(a)];
    m.box("fx", "teal", add([.5, .082, .5], mul(d, .22)), mul(d, .07), [0, .002, 0], mul(t, .006));
    if (i % 2 === 0) m.box("fx", "teal", add([.5, .082, .5], mul(d, .14)), mul(d, .006), [0, .002, 0], mul(t, .04));
  }
  // The blade pedestal: a tapered triangular wedge rising from the plate, holding the ring.
  const wedge = (y, half, depth) => [[-half, -depth], [half, -depth], [0, depth]];
  for (let s = 0; s < 4; s++) {
    const y0 = .08 + s * .1, y1 = y0 + .1, w0 = .17 - s * .035, w1 = .17 - (s + 1) * .035;
    const lower = wedge(y0, w0, .06).map(([x, z]) => [.5 + x, y0, .5 + z]), upper = wedge(y1, w1, .045).map(([x, z]) => [.5 + x, y1, .5 + z]);
    for (let k = 0; k < 3; k++) m.poly("body", k === 2 ? "stone" : "stone_dark", [lower[k], lower[(k + 1) % 3], upper[(k + 1) % 3], upper[k]], null, true);
    if (s === 0) m.poly("body", "slate", [...lower].reverse(), null, true);
    if (s === 3) m.poly("body", "stone", upper, null, true);
  }
  m.box("fx", "teal", [.5, .3, .5 - .055], [.03, 0, 0], [0, .09, 0], [0, 0, .002]);
  m.lathe("body", "steel", [.5, .48, .5], [0, 1, 0], [[0, 0], [.05, 0], [.05, .06], [.03, .08], [0, .08]], 12);
  // Shell 0: the tilted ring with rune blocks, rotating slowly, fast with drones aboard.
  const centre = [.5, .62, .5], tilt = .2, axis = norm([0, Math.cos(tilt), Math.sin(tilt)]), u = perp(axis), w = cross(axis, u);
  const onRing = (t, r) => add(centre, add(mul(u, Math.cos(t) * r), mul(w, Math.sin(t) * r)));
  const rail = [], side = [];
  for (let s = 0; s < 40; s++) { rail.push(onRing(2 * Math.PI * s / 40, .3)); side.push(axis); }
  m.ribbon("shell_0", rail, side, .03, .045, "steel", "slate", true);
  const glow = [], glowSide = [];
  for (let s = 0; s < 40; s++) { glow.push(onRing(2 * Math.PI * s / 40, .324)); glowSide.push(axis); }
  m.ribbon("shell_0", glow, glowSide, .008, .004, "teal", "teal", true);
  for (let i = 0; i < 4; i++) {
    const t = i * Math.PI / 2 + Math.PI / 4, p = onRing(t, .3), out = norm(sub(p, centre)), tan = cross(axis, out);
    m.box("shell_0", k => (k === 1 ? "slate" : "steel"), p, mul(out, .045), mul(axis, .035), mul(tan, .05));
    m.box("shell_0", "teal", add(p, mul(axis, .036)), mul(out, .02), mul(axis, .002), mul(tan, .02));
    // Spokes from the ring in toward the core.
    m.tube("shell_0", "steel", add(p, mul(out, -.04)), add(centre, mul(out, .1)), .012, 6);
  }
  // Core: the shining orb at the ring's centre.
  m.sphere("core", "core_light", centre, .075, 8, 12);
  m.sphere("core", "teal", centre, .09, 6, 10);
  // Shells 1..3: three curved shards floating above the ring, circling and bobbing.
  for (let i = 1; i <= 3; i++) {
    const a = (i - 1) * 2 * Math.PI / 3, group = `shell_${i}`;
    const path = [], sideS = [];
    for (let s = 0; s <= 6; s++) {
      const f = s / 6, b = a - .35 + f * .7, r = .3;
      path.push([.5 + Math.cos(b) * r, .9 + Math.sin(f * Math.PI) * .07, .5 + Math.sin(b) * r]);
      sideS.push([0, 1, 0]);
    }
    m.ribbon(group, path, sideS, .06, .025, "steel", "slate");
    m.box(group, "teal", path[3], [.012, 0, 0], [0, .008, 0], [0, 0, .012]);
  }
  m.write(["body", "core", "fx", "shell_0", "shell_1", "shell_2", "shell_3"]);
  return { shells: 4, height: 1.05 };
}

// --- the Ex-Twins lab sphere (s02) ---------------------------------------------------------------
function twinsDock() {
  const m = new Mesh("twins_drone_dock", {
    steel: [[.78, .8, .84], 0, "relics_addon:block/ship/steel"],
    steel_dark: [[.32, .33, .38], 0],
    chrome: [[.88, .9, .94], 0],
    interior: [[.05, .04, .08], 0],
    violet: [[.65, .3, 1], 1],
    violet_dim: [[.3, .12, .5], .3],
    plexus: [[.75, .85, 1], .9],
    node: [[.9, .7, 1], 1],
  });
  const centre = [.5, .5, .5], R = .36;
  // Stand: three legs and a ring that cradles the sphere.
  m.lathe("body", i => (i === 1 ? "chrome" : "steel_dark"), [.5, 0, .5], [0, 1, 0], [[.3, 0], [.3, .03], [.26, .05], [.22, .05], [.22, .02], [0, .02]], 24);
  for (let i = 0; i < 3; i++) {
    const a = i * 2 * Math.PI / 3 + Math.PI / 6, d = [Math.cos(a), 0, Math.sin(a)];
    m.tube("body", "steel_dark", add([.5, .02, .5], mul(d, .26)), add([.5, .2, .5], mul(d, .31)), .02, 8);
  }
  // The hull with three oval cutouts; a rim ribbon runs round each so the sheet shows no raw edge.
  const cuts = [{ d: norm([0, .35, -1]), ra: .55, rb: .32, spin: .5 }, { d: norm([1, .2, .6]), ra: .5, rb: .3, spin: -.4 }, { d: norm([-1, -.1, .6]), ra: .5, rb: .3, spin: .3 }];
  const inCut = (dir, cut, scale = 1) => {
    const u = rotate(perp(cut.d), cut.d, cut.spin), w = cross(cut.d, u);
    const a = Math.atan2(dot(dir, u), dot(dir, cut.d)), b = Math.atan2(dot(dir, w), dot(dir, cut.d));
    return dot(dir, cut.d) > 0 && (a / cut.ra) ** 2 + (b / cut.rb) ** 2 < scale * scale;
  };
  m.sphere("body", (t, p, dir) => (cuts.some(cut => inCut(dir, cut)) ? null : dir[1] < -.6 ? "steel_dark" : "steel"), centre, R, 18, 36, [3, 2]);
  for (const cut of cuts) {
    const u = rotate(perp(cut.d), cut.d, cut.spin), w = cross(cut.d, u);
    const path = [], side = [];
    for (let s = 0; s < 36; s++) {
      const t = 2 * Math.PI * s / 36;
      const dir = norm(add(add(mul(cut.d, Math.cos(cut.ra * Math.cos(t)) * Math.cos(cut.rb * Math.sin(t))), mul(u, Math.sin(cut.ra * Math.cos(t)))), mul(w, Math.sin(cut.rb * Math.sin(t)))));
      path.push(add(centre, mul(dir, R + .01)));
      side.push(dir);
    }
    m.ribbon("body", path, side, .05, .035, "chrome", "steel_dark", true);
    const inner = path.map(p => add(centre, mul(norm(sub(p, centre)), R - .03)));
    m.ribbon("fx", inner, side, .012, .008, "violet", "violet", true);
  }
  m.sphere("body", (t, p, dir) => (cuts.some(cut => inCut(dir, cut, 1.15)) ? null : "interior"), centre, R - .06, 10, 18);
  // Engine pods seen inside the lower cutouts: small chrome drums with violet rings.
  for (const cut of cuts.slice(1)) {
    const at = add(centre, mul(cut.d, .18));
    m.lathe("body", i => (i === 1 ? "steel_dark" : "chrome"), at, cut.d, [[0, -.05], [.07, -.05], [.07, .05], [.05, .06], [0, .06]], 12);
    m.lathe("fx", "violet", at, cut.d, [[.072, -.01], [.072, .01]], 12);
  }
  // Core: the plexus, an icosahedral web of thin struts with lit nodes round a dark nucleus.
  const phi = (1 + Math.sqrt(5)) / 2, ico = [];
  for (const [a, b] of [[1, phi], [1, -phi], [-1, phi], [-1, -phi]]) ico.push(norm([0, a, b]), norm([a, b, 0]), norm([b, 0, a]));
  const r = .15;
  for (let i = 0; i < ico.length; i++) for (let j = i + 1; j < ico.length; j++) {
    if (len(sub(ico[i], ico[j])) < 1.1) m.tube("core", "plexus", add(centre, mul(ico[i], r)), add(centre, mul(ico[j], r)), .007, 4);
  }
  for (const v of ico) m.sphere("core", "node", add(centre, mul(v, r)), .018, 3, 5);
  for (const v of ico.slice(0, 6)) m.tube("core", "plexus", add(centre, mul(v, r)), add(centre, mul(v, .05)), .005, 4);
  m.sphere("core", "interior", centre, .05, 6, 8);
  m.sphere("core", "violet_dim", centre, .052, 4, 6);
  m.write(["body", "core", "fx"]);
  return { shells: 0, height: .9 };
}

// --- model JSON ----------------------------------------------------------------------------------
function writeModels(id, parts, generator) {
  const obj = (visible, extra = {}) => ({
    loader: "neoforge:obj",
    model: `relics_addon:models/block/${id}.obj`,
    automatic_culling: false,
    shade_quads: true,
    emissive_ambient: true,
    render_type: "minecraft:cutout",
    textures: { particle: "relics_addon:block/ship/steel" },
    visibility: Object.fromEntries(parts.map(part => [part, visible.includes(part)])),
    ...extra,
  });
  const write = (path, json) => { mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, JSON.stringify(json, null, 2) + "\n"); };
  write(join(MODELS, `${id}.json`), obj(["body"]));
  write(join(MODELS, `${id}_on.json`), obj(generator ? ["body", "ring"] : ["body"]));
  for (const part of parts) if (part !== "body" && part !== "ring") write(join(PARTS, `${id}_${part}.json`), obj([part]));
  const scale = generator ? .62 : .78;
  write(join(ITEMS, `${id}.json`), obj(parts.filter(part => part !== "ring"), {
    gui_light: "side",
    display: {
      gui: { rotation: [30, 225, 0], translation: [0, generator ? -1.5 : 0, 0], scale: [scale, scale, scale] },
      ground: { translation: [0, 3, 0], scale: [.25, .25, .25] },
      fixed: { rotation: [0, 180, 0], scale: [.5, .5, .5] },
      thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [.375, .375, .375] },
      thirdperson_lefthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [.375, .375, .375] },
      firstperson_righthand: { rotation: [0, 45, 0], scale: [.4, .4, .4] },
      firstperson_lefthand: { rotation: [0, 225, 0], scale: [.4, .4, .4] },
    },
  }));
}

writeTextures();
const built = {
  rf_ship_shield_generator: [rfGenerator(), true],
  mana_ship_shield_generator: [manaGenerator(), true],
  twins_ship_shield_generator: [twinsGenerator(), true],
  rf_drone_dock: [rfDock(), false],
  mana_drone_dock: [manaDock(), false],
  twins_drone_dock: [twinsDock(), false],
};
for (const [id, [{ shells }, generator]] of Object.entries(built)) {
  const parts = ["body", ...(generator ? ["ring"] : []), "core", "fx", ...Array.from({ length: shells }, (_, i) => `shell_${i}`)];
  writeModels(id, parts, generator);
}
