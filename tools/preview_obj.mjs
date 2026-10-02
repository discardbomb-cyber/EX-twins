#!/usr/bin/env node
// Software preview of OBJ models: renders a model from several sides (orthographic, z-buffered,
// lambert plus the material's Ka as emission, textures sampled from the mod's atlas files) onto a
// magenta background, so holes and missing faces show up before the model goes in the game.
//   node tools/preview_obj.mjs <id> [--pose rest|on|docked] [--size 256] [--out work/preview-<id>.png]
// <id> is a model under models/block (e.g. rf_drone_dock) or a path to an .obj. The `on` and
// `docked` poses move the groups the way ShipDeviceRenderer does at the end of its animations.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { basename, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const { PNG } = require("pngjs");
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const ASSETS = join(ROOT, "src/main/resources/assets/relics_addon");

const args = process.argv.slice(2);
const option = (name, fallback) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : fallback; };
const id = args.find(a => !a.startsWith("--") && args[args.indexOf(a) - 1]?.startsWith("--") !== true);
if (!id) { console.error("usage: preview_obj.mjs <id|file.obj> [--pose rest|on|docked] [--size N] [--out file]"); process.exit(2); }
const file = id.endsWith(".obj") ? id : join(ASSETS, "models/block", `${id}.obj`);
const name = basename(file, ".obj");
const pose = option("--pose", "rest");
const size = Number(option("--size", 256));
const ticks = Number(option("--time", 0)) * 20;
const activation = Number(option("--open", pose === "on" ? 1 : 0));
const ease = x => x*x*(3-2*x);
const share = ease(activation), rad = Math.PI/180;
function animated(group) {
  if (name === "rf_ship_shield_generator") {
    const spin=about([.5,.78,.5],[0,1,0],ticks*1.7*share*rad);
    if(group==="core" || group==="fx") return spin;
    if(group.startsWith("shell_")) {
      const i=Number(group.slice(6)), a=-Math.PI/2+i*Math.PI*2/3, n=[Math.cos(a),0,Math.sin(a)];
      const pivot=add([.5,[.62,1.12,.97][i],.5],mul(n,.395));
      return p=>about([.5,.78,.5],[0,1,0],ticks*.65*share*rad)(add(add(about(pivot,n,.15*share)(p),mul(n,.055*share)),[0,Math.sin(ticks*.045+i*Math.PI*2/3)*.012*share,0]));
    }
  }
  if(name === "mana_ship_shield_generator" && (group==="core" || group.startsWith("shell_"))) {
    const outer=p=>add(about([.5,.62,.5],[0,1,0],ticks*(1+2*activation)*rad)(p),[0,Math.sin(ticks*.05)*.02*activation,0]);
    if(group==="core") return p=>outer(about([.5,.62,.5],[1,.4,.6],ticks*3*activation*rad)(add([.5,.62,.5],mul(sub(p,[.5,.62,.5]),.4+.6*share))));
    const i=Number(group.slice(6)), a=i*Math.PI*2/3,n=[Math.cos(a),0,Math.sin(a)];
    const s=ease(Math.max(0,Math.min(1,activation*3-i)));
    return p=>outer(add(about([.5,.62,.5],n,18*s*rad)(p),mul(n,.13*s)));
  }
  if(name === "twins_ship_shield_generator") {
    if(group.startsWith("shell_")) {
      const i=Number(group.slice(6)), tilt=.35+.8*((i*7)%20)/19, az=i*2.399963;
      const axis=[Math.cos(az)*Math.sin(tilt),Math.cos(tilt),Math.sin(az)*Math.sin(tilt)];
      const speed=(i%2===0?1:-1)*(.65+.11*(i%9));
      return about([.5,.62,.5],axis,(ticks*speed*share+i*137.5)*rad);
    }
    if(group==="core") return about([.5,.62,.5],[0,1,0],ticks*2.5*share*rad);
    if(group==="fx") return p=>[p[0],.3+(p[1]-.3)*share,p[2]];
  }
  return null;
}
const out = option("--out", join(ROOT, "work", `preview-${name}${pose === "rest" ? "" : "-" + pose}.png`));

// --- vectors -------------------------------------------------------------------------------------
const add = (a, b) => a.map((v, i) => v + b[i]);
const sub = (a, b) => a.map((v, i) => v - b[i]);
const mul = (a, s) => a.map(v => v * s);
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = a => { const l = Math.hypot(...a) || 1; return a.map(v => v / l); };
function rotate(p, axis, angle) {
  const a = norm(axis), c = Math.cos(angle), s = Math.sin(angle);
  return add(add(mul(p, c), mul(cross(a, p), s)), mul(a, dot(a, p) * (1 - c)));
}
const about = (pivot, axis, angle) => p => add(pivot, rotate(sub(p, pivot), axis, angle));

// --- the poses the renderer ends in --------------------------------------------------------------
const C = [.5, .5, .5];
const POSES = {
  rf_ship_shield_generator: { on: group => {
    if (!group.startsWith("shell_")) return null;
    const i=Number(group.slice(6)), a=-Math.PI/2+i*Math.PI*2/3, n=[Math.cos(a),0,Math.sin(a)];
    const pivot=add([.5,[.62,1.12,.97][i],.5],mul(n,.395));
    return p=>add(about(pivot,n,.15)(p),mul(n,.055));
  } },
  mana_ship_shield_generator: { on: group => {
    if (!group.startsWith("shell_")) return null;
    const i=Number(group.slice(6)), angle=i*Math.PI*2/3, n=[Math.cos(angle),0,Math.sin(angle)];
    return p=>add(about([.5,.62,.5],n,Math.PI/10)(p),mul(n,.13));
  } },
  rf_drone_dock: { docked: group => {
    const i = Number(group.replace("shell_", ""));
    if (Number.isNaN(i)) return null;
    const a = i * 2 * Math.PI / 3 + Math.PI / 2, d = [Math.cos(a), 0, Math.sin(a)];
    return p => add(p, mul(d, .13));
  } },
  mana_drone_dock: { docked: group => (group === "shell_0" ? about([.5, .62, .5], [0, 1, 0], .8) : group.startsWith("shell_") ? about(C, [0, 1, 0], 1.2) : null) },
};
const hidden = { rest: new Set(["ring"]), on: new Set(), docked: new Set(["ring"]) }[pose] ?? new Set();
if (name === "rf_ship_shield_generator" && pose === "rest") hidden.add("fx");
if(activation<=.01 && name.endsWith("ship_shield_generator")) { hidden.add("fx"); if(name.startsWith("mana")) hidden.add("core"); }

// --- load ----------------------------------------------------------------------------------------
const textures = new Map();
function texture(ref) {
  if (!textures.has(ref)) {
    if (ref.endsWith(".png") && existsSync(ref)) {
      textures.set(ref, PNG.sync.read(readFileSync(ref)));
      return textures.get(ref);
    }
    const [ns, path] = ref.split(":");
    const png = join(ASSETS, "..", ns, "textures", `${path}.png`);
    const tex=existsSync(png)?PNG.sync.read(readFileSync(png)):null;
    if(tex && existsSync(png+".mcmeta") && tex.height>tex.width) {
      const meta=JSON.parse(readFileSync(png+".mcmeta","utf8")), height=tex.width;
      const frame=Math.floor(ticks/(meta.animation?.frametime??1))%(tex.height/height);
      tex.data=tex.data.subarray(frame*height*tex.width*4,(frame+1)*height*tex.width*4); tex.height=height;
    }
    textures.set(ref,tex);
  }
  return textures.get(ref);
}
function sample(ref, u, v) {
  const t = texture(ref);
  if (!t) return [1, 1, 1];
  const x = ((Math.floor(u * t.width) % t.width) + t.width) % t.width, y = ((Math.floor(v * t.height) % t.height) + t.height) % t.height;
  const i = (y * t.width + x) * 4;
  return [t.data[i] / 255, t.data[i + 1] / 255, t.data[i + 2] / 255];
}
const materials = {};
for (const block of readFileSync(file.replace(/\.obj$/, ".mtl"), "utf8").split("newmtl ").slice(1)) {
  const lines = block.split(/\r?\n/), mat = { kd: [1, 1, 1], ka: 0, map: null };
  for (const line of lines.slice(1)) {
    const [key, ...rest] = line.trim().split(/\s+/);
    if (key === "Kd") mat.kd = rest.map(Number);
    else if (key === "Ka") mat.ka = Number(rest[0]);
    else if (key === "map_Kd") mat.map = rest[0];
  }
  materials[lines[0].trim()] = mat;
}
if(activation<=.01 && name.endsWith("ship_shield_generator")) for(const mat of Object.values(materials)) mat.ka=0;
const v = [], vt = [], vn = [], tris = [];
const transforms = new Map(), posedVertices = new Map(), posedNormals = new Map();
function corner(pi,ti,ni,group,transform) {
  const key=`${group}:${pi}`;
  if(!posedVertices.has(key)) posedVertices.set(key,transform?transform(v[pi-1]):v[pi-1]);
  const nk=`${key}:${ni}`;
  if(ni && !posedNormals.has(nk)) posedNormals.set(nk,transform?norm(sub(transform(add(v[pi-1],vn[ni-1])),posedVertices.get(key))):vn[ni-1]);
  return {p:posedVertices.get(key),t:ti?vt[ti-1]:[.5,.5],n:ni?posedNormals.get(nk):null};
}
let group = "default", material = null;
for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
  const parts = line.trim().split(/\s+/);
  if (parts[0] === "v") v.push(parts.slice(1, 4).map(Number));
  else if (parts[0] === "vt") vt.push(parts.slice(1, 3).map(Number));
  else if (parts[0] === "vn") vn.push(parts.slice(1, 4).map(Number));
  else if (parts[0] === "g") group = parts[1];
  else if (parts[0] === "usemtl") material = parts[1];
  else if (parts[0] === "f") {
    if (hidden.has(group)) continue;
    const corners = parts.slice(1).map(tok => tok.split("/").map(s => (s === "" ? 0 : Number(s))));
    if(!transforms.has(group)) transforms.set(group,args.includes("--time") ? animated(group) : POSES[name]?.[pose]?.(group) ?? null);
    const transform = transforms.get(group);
    for (let i = 1; i + 1 < corners.length; i++) {
      tris.push({ material, transform, corners: [corners[0], corners[i], corners[i + 1]].map(([pi,ti,ni])=>corner(pi,ti,ni,group,transform)) });
    }
  }
}
let lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
for (const t of tris) for (const c of t.corners) { lo = lo.map((x, i) => Math.min(x, c.p[i])); hi = hi.map((x, i) => Math.max(x, c.p[i])); }
const fixed = args.includes("--fixed-camera");
const centre = fixed ? [.5,.70,.5] : mul(add(lo, hi), .5);

// --- render --------------------------------------------------------------------------------------
/** Views: name, camera forward direction (towards the model) and up. */
let VIEWS = [
  ["front", norm([0, -.35, 1]), [0, 1, 0]],
  ["corner", norm([-1, -.6, 1]), [0, 1, 0]],
  ["side", norm([-1, -.1, 0]), [0, 1, 0]],
  ["top", [0, -1, 0], [0, 0, -1]],
  ["below", norm([.6, 1, -.8]), [0, 1, 0]],
  ["back", norm([.3, -.3, -1]), [0, 1, 0]],
];
const LIGHT = norm([-.4, 1, -.6]);
const selectedView = option("--view", "all");
if (selectedView !== "all") {
  VIEWS = VIEWS.filter(([name]) => name === selectedView);
  if (!VIEWS.length) throw new Error(`Unknown view: ${selectedView}`);
}
const background = option("--background", "magenta") === "dark" ? [22, 26, 32] : [255, 0, 255];
const W = size * VIEWS.length, H = size;
const png = new PNG({ width: W, height: H });
png.data.fill(0);
for (let i = 0; i < W * H; i++) { for (let k=0;k<3;k++) png.data[i * 4 + k] = background[k]; png.data[i * 4 + 3] = 255; }

VIEWS.forEach(([label, forward, upHint], viewIndex) => {
  const right = norm(cross(forward, upHint)), up = cross(right, forward);
  // Fit the posed mesh in camera space: an oblique view can exceed the world-space box width.
  let minX=Infinity, maxX=-Infinity, minY=Infinity, maxY=-Infinity;
  for (const tri of tris) for (const corner of tri.corners) {
    const d=sub(corner.p,centre), x=dot(d,right), y=dot(d,up);
    minX=Math.min(minX,x); maxX=Math.max(maxX,x); minY=Math.min(minY,y); maxY=Math.max(maxY,y);
  }
  const extent=fixed?1.85:Math.max(maxX-minX,maxY-minY)*1.12, offsetX=fixed?0:(minX+maxX)/2, offsetY=fixed?0:(minY+maxY)/2;
  const depth = new Float32Array(size * size).fill(Infinity);
  const project = p => { const d = sub(p, centre); return [((dot(d, right)-offsetX) / extent + .5) * size, (.5 - (dot(d, up)-offsetY) / extent) * size, dot(d, forward)]; };
  for (const tri of tris) {
    const mat = materials[tri.material] ?? { kd: [1, 0, 1], ka: 0, map: null };
    const s = tri.corners.map(c => project(c.p));
    const faceN = norm(cross(sub(tri.corners[1].p, tri.corners[0].p), sub(tri.corners[2].p, tri.corners[0].p)));
    // Back faces are skipped as the game culls them; double-sided geometry supplies its own backs.
    if (dot(faceN, forward) > 0) continue;
    const minX = Math.max(0, Math.floor(Math.min(s[0][0], s[1][0], s[2][0]))), maxX = Math.min(size - 1, Math.ceil(Math.max(s[0][0], s[1][0], s[2][0])));
    const minY = Math.max(0, Math.floor(Math.min(s[0][1], s[1][1], s[2][1]))), maxY = Math.min(size - 1, Math.ceil(Math.max(s[0][1], s[1][1], s[2][1])));
    const area = (s[1][0] - s[0][0]) * (s[2][1] - s[0][1]) - (s[2][0] - s[0][0]) * (s[1][1] - s[0][1]);
    if (Math.abs(area) < 1e-9) continue;
    for (let y = minY; y <= maxY; y++) for (let x = minX; x <= maxX; x++) {
      const px = x + .5, py = y + .5;
      let w0 = ((s[1][0] - px) * (s[2][1] - py) - (s[2][0] - px) * (s[1][1] - py)) / area;
      let w1 = ((s[2][0] - px) * (s[0][1] - py) - (s[0][0] - px) * (s[2][1] - py)) / area;
      let w2 = 1 - w0 - w1;
      if (w0 < -1e-4 || w1 < -1e-4 || w2 < -1e-4) continue;
      const z = w0 * s[0][2] + w1 * s[1][2] + w2 * s[2][2];
      if (z >= depth[y * size + x]) continue;
      depth[y * size + x] = z;
      const n = norm([0, 1, 2].map(i => w0 * (tri.corners[0].n ?? faceN)[i] + w1 * (tri.corners[1].n ?? faceN)[i] + w2 * (tri.corners[2].n ?? faceN)[i]));
      const uv = [0, 1].map(i => w0 * tri.corners[0].t[i] + w1 * tri.corners[1].t[i] + w2 * tri.corners[2].t[i]);
      const tex = mat.map ? sample(mat.map, uv[0], uv[1]) : [1, 1, 1];
      const lambert = .35 + .65 * Math.max(0, dot(n, LIGHT));
      const shade = Math.min(1, lambert + mat.ka);
      const i = (y * W + viewIndex * size + x) * 4;
      for (let k = 0; k < 3; k++) png.data[i + k] = Math.round(Math.min(1, mat.kd[k] * tex[k] * shade) * 255);
    }
  }
  // Label strip: the view's index as tick marks along the bottom so the sheet reads left to right.
  for (let k = 0; k <= viewIndex; k++) for (let dy = 0; dy < 3; dy++) for (let dx = 0; dx < 6; dx++) {
    const i = ((H - 1 - dy) * W + viewIndex * size + 4 + k * 9 + dx) * 4;
    png.data[i] = 0; png.data[i + 1] = 0; png.data[i + 2] = 0;
  }
});
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, PNG.sync.write(png));
console.log(`${out}: ${tris.length} triangles, ${VIEWS.map(v => v[0]).join(" / ")}, pose ${pose}`);
