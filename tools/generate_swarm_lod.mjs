#!/usr/bin/env node
// Builds the mid-detail ("swarm") OBJ meshes the renderer uses for drones a few blocks away:
// src/main/resources/assets/relics_addon/models/item/{rf,mana}_drone_lod.{obj,mtl}.
//
//   node tools/generate_swarm_lod.mjs
//
// A Node port of the former generate_swarm_lod.py with rounder silhouettes and the RF optic and wing
// light strips kept, so a drone reads as the same machine as its full model. Geometry is authored in
// model units (16 per block, centred on 8) and each mesh must stay within its face budget.
import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const MODELS = join(dirname(fileURLToPath(import.meta.url)), "../src/main/resources/assets/relics_addon/models/item");
const TAU = Math.PI * 2;
const BUDGET = 700;

const RF_MATERIALS = {
  silver: [[.47, .51, .55], 0], pearl: [[.70, .73, .76], 0], edge: [[.92, .95, .98], 0],
  graphite: [[.045, .055, .068], 0], rubber: [[.019, .024, .032], 0], steel: [[.24, .28, .33], 0],
  red_rim: [[.34, .012, .025], 0], red_glass: [[.78, .012, .035], .28], iris: [[.17, .022, .028], 0],
  copper: [[.40, .24, .13], 0], cyan: [[.32, .77, 1.0], 1], white: [[.93, .98, 1.0], 1],
};
for (let tone = 0; tone < 16; tone++) {
  const t = tone / 15;
  RF_MATERIALS[`lens_${String(tone).padStart(2, "0")}`] = [[.14 + .80 * t, .006 + .38 * Math.max(0, (t - .70) / .30),
    .018 + .30 * Math.max(0, (t - .70) / .30)], .035];
}
const MANA_MATERIALS = {
  ceramic: [[.79, .81, .78], 0], ceramic_shadow: [[.41, .48, .55], 0], gold: [[.59, .40, .13], 0],
  gold_edge: [[.91, .71, .30], 0], navy: [[.024, .04, .09], 0], mana_core: [[.045, .45, .83], .35],
  mana_light: [[.28, .89, 1], .85], mana_ice: [[.66, .92, 1], .55], obsidian: [[.075, .066, .11], 0],
  armor: [[.16, .14, .22], 0], armor_edge: [[.31, .28, .38], 0], silver: [[.50, .49, .57], 0],
  violet: [[.57, .10, .84], .55], amethyst: [[.29, .05, .45], .22], lilac: [[.83, .47, 1], .85],
};

// --- vectors --------------------------------------------------------------------------------
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const mul = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const length = a => Math.sqrt(dot(a, a));
const unit = a => { const l = length(a); if (l < 1e-9) throw new Error("Degenerate normal"); return mul(a, 1 / l); };
const mean = points => mul(points.reduce(add, [0, 0, 0]), 1 / points.length);

class Mesh {
  constructor(materials) { this.faces = []; this.group = "hull"; this.materials = materials; }

  /** Adds a polygon wound so its normal points along {@code outward}; normals default to the flat face normal. */
  face(points, material, outward, normals = null) {
    let normal = cross(sub(points[1], points[0]), sub(points[2], points[0]));
    if (length(normal) < 1e-9) return;
    if (dot(normal, outward) < 0) {
      points = [...points].reverse();
      if (normals) normals = [...normals].reverse();
      normal = mul(normal, -1);
    }
    this.faces.push({ group: this.group, material, points, normals: normals || points.map(() => unit(normal)) });
  }

  export(item, scale) {
    const vertices = [], normals = [], faces = [];
    let lastGroup = null, lastMaterial = null;
    const order = new Map();
    for (const face of this.faces) if (!order.has(face.group)) order.set(face.group, order.size);
    const sorted = [...this.faces].sort((a, b) => order.get(a.group) - order.get(b.group));
    for (const { group, material, points, normals: ns } of sorted) {
      if (group !== lastGroup) { faces.push(`g ${group}`); lastGroup = group; }
      if (material !== lastMaterial) { faces.push(`usemtl ${material}`); lastMaterial = material; }
      const indices = points.map((point, k) => {
        vertices.push("v " + point.map(x => ((x * scale + 8) / 16).toFixed(7)).join(" "));
        normals.push("vn " + ns[k].map(x => x.toFixed(7)).join(" "));
        return `${vertices.length}/1/${vertices.length}`;
      });
      faces.push("f " + indices.join(" "));
    }
    writeFileSync(join(MODELS, `${item}.obj`), ["# Relics-P04; model coordinates in block units; front faces -Z",
      `mtllib ${item}.mtl`, ...vertices, "vt 0.5 0.5", ...normals, ...faces, ""].join("\n"), "ascii");
    const mtl = [];
    for (const [name, [color, emission]] of Object.entries(this.materials)) {
      mtl.push(`newmtl ${name}`, "Kd " + color.join(" "), `Ka ${emission} ${emission} ${emission}`,
        "map_Kd relics_addon:item/materials/rf_mesh_white", "");
    }
    writeFileSync(join(MODELS, `${item}.mtl`), mtl.join("\n"), "ascii");
    return { faces: this.faces.length, vertices: vertices.length, groups: [...order.keys()] };
  }
}

const spherical = (radius, theta, phi) => [radius * Math.sin(theta) * Math.cos(phi), radius * Math.sin(theta) * Math.sin(phi), -radius * Math.cos(theta)];

/** A patch of sphere with smooth normals; quads touching a pole collapse to triangles. */
function patch(mesh, radius, t0, t1, p0, p1, material, rows, columns) {
  const grid = [];
  for (let i = 0; i <= rows; i++) {
    grid.push([]);
    for (let j = 0; j <= columns; j++) grid[i].push(spherical(radius, t0 + (t1 - t0) * i / rows, p0 + (p1 - p0) * j / columns));
  }
  for (let i = 0; i < rows; i++) for (let j = 0; j < columns; j++) {
    const unique = [];
    for (const point of [grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]]) {
      if (!unique.some(other => length(sub(point, other)) < 1e-8)) unique.push(point);
    }
    if (unique.length >= 3) mesh.face(unique, material, mean(unique), unique.map(unit));
  }
}

/** Revolves a (radius, z) profile about the z axis through {@code center}. */
function lathe(mesh, profile, material, { center = [0, 0, 0], segments = 32, smooth = false } = {}) {
  if (profile[0][0] > profile[profile.length - 1][0]) profile = [...profile].reverse();
  const directions = profile.slice(1).map((q, k) => unit2([q[1] - profile[k][1], profile[k][0] - q[0]]));
  for (let k = 0; k + 1 < profile.length; k++) {
    const [r0, z0] = profile[k], [r1, z1] = profile[k + 1];
    for (let i = 0; i < segments; i++) {
      const a = TAU * i / segments, b = TAU * (i + 1) / segments;
      let p = [add(center, [r0 * Math.cos(a), r0 * Math.sin(a), z0]), add(center, [r0 * Math.cos(b), r0 * Math.sin(b), z0]),
        add(center, [r1 * Math.cos(b), r1 * Math.sin(b), z1]), add(center, [r1 * Math.cos(a), r1 * Math.sin(a), z1])];
      const normal = [(z1 - z0) * Math.cos((a + b) / 2), (z1 - z0) * Math.sin((a + b) / 2), r0 - r1];
      let ns = null;
      if (smooth) {
        const n0 = unit2(add2(directions[Math.max(0, k - 1)], directions[k]));
        const n1 = unit2(add2(directions[k], directions[Math.min(directions.length - 1, k + 1)]));
        ns = [[n0, a], [n0, b], [n1, b], [n1, a]].map(([n, phi]) => [n[0] * Math.cos(phi), n[0] * Math.sin(phi), n[1]]);
      }
      if (r0 === 0) { p = [p[0], p[2], p[3]]; if (ns) ns = [[0, 0, -1], ns[2], ns[3]]; }
      else if (r1 === 0) { p = p.slice(0, 3); if (ns) ns = [ns[0], ns[1], [0, 0, -1]]; }
      mesh.face(p, material, normal, ns);
    }
  }
}
const unit2 = v => { const l = Math.hypot(v[0], v[1]); return [v[0] / l, v[1] / l]; };
const add2 = (a, b) => [a[0] + b[0], a[1] + b[1]];

function surface(mesh, grid, material, back = false) {
  const sign = back ? -1 : 1;
  for (let i = 0; i + 1 < grid.length; i++) for (let j = 0; j + 1 < grid[0].length; j++) {
    const points = [grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]];
    mesh.face(points, material, mul(mean(points), sign), points.map(p => mul(unit(p), sign)));
  }
}

/** A curved panel closed with an inner skin and edge walls. */
function closedRadialShell(mesh, grid, front, back, edge, thickness) {
  const inner = grid.map(row => row.map(p => sub(p, mul(unit(p), thickness))));
  surface(mesh, grid, front);
  surface(mesh, inner, back, true);
  const boundary = values => [...values[0], ...values.slice(1).map(row => row[row.length - 1]),
    ...[...values[values.length - 1].slice(0, -1)].reverse(), ...values.slice(1, -1).reverse().map(row => row[0])];
  const outerLoop = boundary(grid), innerLoop = boundary(inner);
  const original = cross(sub(grid[1][0], grid[0][0]), sub(grid[1][1], grid[0][0]));
  const sign = dot(original, grid[0][0]) > 0 ? 1 : -1;
  outerLoop.forEach((a, index) => {
    const following = (index + 1) % outerLoop.length;
    const points = [a, outerLoop[following], innerLoop[following], innerLoop[index]];
    mesh.face(points, edge, mul(cross(sub(points[1], points[0]), sub(points[2], points[0])), sign));
  });
}

/** An extruded polygon in the XY plane between z = front and z = back, turned by {@code angle}. */
function prism(mesh, polygon, front, back, material, edge, angle = 0) {
  const co = Math.cos(angle), si = Math.sin(angle);
  const point = (p, z) => [p[0] * co - p[1] * si, p[0] * si + p[1] * co, z];
  const fronts = polygon.map(p => point(p, front)), backs = polygon.map(p => point(p, back));
  for (let i = 1; i + 1 < polygon.length; i++) {
    mesh.face([fronts[0], fronts[i], fronts[i + 1]], material, [0, 0, -1]);
    mesh.face([backs[0], backs[i], backs[i + 1]], material, [0, 0, 1]);
  }
  const middle = mean(fronts);
  polygon.forEach((_, i) => {
    const j = (i + 1) % polygon.length, out = sub(mul(add(fronts[i], fronts[j]), .5), middle);
    out[2] = 0;
    mesh.face([fronts[i], fronts[j], backs[j], backs[i]], edge, out);
  });
}

function rotateFaces(mesh, start, axis, angle) {
  const [x, y, z] = unit(axis), c = Math.cos(angle), s = Math.sin(angle);
  const m = [[c + x * x * (1 - c), x * y * (1 - c) - z * s, x * z * (1 - c) + y * s],
    [y * x * (1 - c) + z * s, c + y * y * (1 - c), y * z * (1 - c) - x * s],
    [z * x * (1 - c) - y * s, z * y * (1 - c) + x * s, c + z * z * (1 - c)]];
  const apply = v => [dot(m[0], v), dot(m[1], v), dot(m[2], v)];
  for (let i = start; i < mesh.faces.length; i++) {
    const f = mesh.faces[i];
    mesh.faces[i] = { ...f, points: f.points.map(apply), normals: f.normals.map(apply) };
  }
}

// --- the drones -----------------------------------------------------------------------------

/** A round silver orb with its red optic and cyan ring on the front (-Z), and four wings with lit emitters. */
function rfDroneLod() {
  const mesh = new Mesh(RF_MATERIALS);
  patch(mesh, 3.04, 0, Math.PI, 0, TAU, "silver", 8, 16);
  lathe(mesh, [[3.08, -.13], [3.13, -.045], [3.13, .045], [3.08, .13]], "steel", { segments: 16 });
  // The optic: graphite housing, red glass, its rim and a cyan ring, all facing forward.
  // The housing is an open collar so the recessed red glass shows through it.
  lathe(mesh, [[.78, -3.25], [.97, -3.17], [.97, -2.93]], "graphite", { segments: 16 });
  lathe(mesh, [[0, -3.16], [.55, -3.13], [.80, -3.05]], "red_glass", { segments: 16, smooth: true });
  lathe(mesh, [[.66, -3.215], [.80, -3.255]], "red_rim", { segments: 16 });
  lathe(mesh, [[1.02, -2.98], [1.14, -2.88]], "cyan", { segments: 16 });
  for (let wing = 0; wing < 4; wing++) {
    const angle = Math.PI / 4 + wing * Math.PI / 2;
    prism(mesh, [[2.85, -.40], [6.10, -.58], [6.80, -.34], [6.80, .34], [6.10, .58], [2.85, .40]], -.24, .16, "graphite", "steel", angle);
    // A light strip along the wing's front face.
    prism(mesh, [[3.30, -.07], [6.35, -.10], [6.35, .10], [3.30, .07]], -.30, -.23, "cyan", "cyan", angle);
    for (const [radial, lateral] of [[4.55, -.22], [5.85, .22]]) {
      const center = [radial * Math.cos(angle) - lateral * Math.sin(angle), radial * Math.sin(angle) + lateral * Math.cos(angle), -.31];
      lathe(mesh, [[0, .03], [.26, .03], [.26, -.13], [.19, -.22]], "steel", { center, segments: 6 });
      lathe(mesh, [[0, -.225], [.17, -.225], [.14, -.26]], "cyan", { center, segments: 6 });
    }
  }
  return mesh;
}

/** A turquoise core in two crossed gold rings, held by six closed, curved ceramic petals. */
function manaDroneLod() {
  const mesh = new Mesh(MANA_MATERIALS);
  patch(mesh, 2.40, 0, Math.PI, 0, TAU, "mana_core", 6, 12);
  for (const [axis, angle] of [[[1, 0, 0], .65], [[0, 1, 0], -.65]]) {
    const start = mesh.faces.length;
    lathe(mesh, [[2.60, -.065], [2.72, -.04], [2.72, .04], [2.60, .065]], "gold", { segments: 16 });
    rotateFaces(mesh, start, axis, angle);
  }
  for (let shell = 0; shell < 6; shell++) {
    const angle = shell * TAU / 6;
    const point = (t, across) => {
      const width = .055 + .32 * Math.pow(Math.sin(Math.PI * t), .65);
      const theta = .41 + t * 2.30, phi = angle + across * width + .16 * Math.sin(t * TAU);
      return spherical(4.22 + .16 * Math.sin(t * Math.PI), theta, phi);
    };
    // Six rows and two columns round out the petal's curve.
    const grid = [];
    for (let row = 0; row <= 6; row++) grid.push([0, 1, 2].map(column => point(row / 6, column - 1)));
    closedRadialShell(mesh, grid, "ceramic", "ceramic_shadow", "gold", .17);
  }
  return mesh;
}

const metadata = {
  rf_drone_lod: rfDroneLod().export("rf_drone_lod", 1.0),
  mana_drone_lod: manaDroneLod().export("mana_drone_lod", 1.18),
};
for (const [name, record] of Object.entries(metadata)) {
  if (record.faces > BUDGET) throw new Error(`${name} exceeds the ${BUDGET} face budget: ${record.faces}`);
}
console.log(JSON.stringify(metadata, null, 2));
