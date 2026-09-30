#!/usr/bin/env node
// Pixel gate over the deterministic GIF galleries (runFeatureGifClient), for the hexagonal migration.
//
//   node tools/compare_frames.mjs --make-mask <out.json> <dirA> <dirB> [<dirC> ...] [--dilate <px>] [--window <frames>]
//       Records, for every relics-*-gif-*.png, the pixels that differ between any of the given runs of the
//       same code (item models animate on wall-clock time), grown by --dilate pixels (default 4) in every
//       direction, as per-row ranges. With --window n (default 0), a frame's mask also takes in what
//       differs in the n frames before and after it in the same sequence, since a wall-clock model that
//       happens to agree in every run in one frame usually differs in the next. Fails if a run lacks a
//       frame or a frame changes size.
//
//   node tools/compare_frames.mjs --baseline <dir> --candidate <dir> [--mask <mask.json>]
//       Prints per-frame counts of differing pixels and exits 1 on a missing (or extra) frame, a size
//       difference, or any differing pixel outside the mask.
//
// The migration's mask (/c/dev/EX-twins-baselines/mask.json) comes from eight runs, s0-a to s0-h, with
// --window 2. Only the hive frames vary: the item icons and each swarm's lead drone use the wall-clock
// item animation, and a lead drone's rare one-pixel flickers slipped past masks from three runs.
//
// Needs pngjs (run `npm install` in tools/ if node_modules is missing).
import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import pngjs from "pngjs";

const { PNG } = pngjs;
const FRAME = /^relics-.*-gif-.*\.png$/;

function usage(message) {
  if (message) console.error(message);
  console.error("Usage:\n  node tools/compare_frames.mjs --make-mask <out.json> <dirA> <dirB> [<dirC> ...] [--dilate <px>] [--window <frames>]\n"
      + "  node tools/compare_frames.mjs --baseline <dir> --candidate <dir> [--mask <mask.json>]");
  process.exit(2);
}

function frames(dir) {
  if (!existsSync(dir)) usage(`No such directory: ${dir}`);
  return readdirSync(dir).filter((name) => FRAME.test(name)).sort();
}

function read(file) {
  const png = PNG.sync.read(readFileSync(file));
  return { width: png.width, height: png.height, data: png.data };
}

/** Grows every set pixel into a (2 radius + 1)-pixel square: one pass per axis, linear time. */
function dilate(mask, width, height, radius) {
  if (radius <= 0) return mask;
  const horizontal = new Uint8Array(mask.length);
  for (let y = 0; y < height; y++) {
    const row = y * width;
    let last = -Infinity;
    for (let x = 0; x < width; x++) {
      if (mask[row + x]) last = x;
      if (x - last <= radius) horizontal[row + x] = 1;
    }
    let next = Infinity;
    for (let x = width - 1; x >= 0; x--) {
      if (mask[row + x]) next = x;
      if (next - x <= radius) horizontal[row + x] = 1;
    }
  }
  const result = new Uint8Array(mask.length);
  for (let x = 0; x < width; x++) {
    let last = -Infinity;
    for (let y = 0; y < height; y++) {
      if (horizontal[y * width + x]) last = y;
      if (y - last <= radius) result[y * width + x] = 1;
    }
    let next = Infinity;
    for (let y = height - 1; y >= 0; y--) {
      if (horizontal[y * width + x]) next = y;
      if (next - y <= radius) result[y * width + x] = 1;
    }
  }
  return result;
}

/** Row ranges [x0, x1) of the set pixels, keyed by row. */
function toRanges(mask, width, height) {
  const rows = {};
  let count = 0;
  for (let y = 0; y < height; y++) {
    const ranges = [];
    let start = -1;
    for (let x = 0; x <= width; x++) {
      const set = x < width && mask[y * width + x];
      if (set && start < 0) start = x;
      if (!set && start >= 0) {
        ranges.push([start, x]);
        count += x - start;
        start = -1;
      }
    }
    if (ranges.length) rows[y] = ranges;
  }
  return { rows, count };
}

function fromRanges(entry, width, height) {
  const mask = new Uint8Array(width * height);
  for (const [row, ranges] of Object.entries(entry.rows ?? {})) {
    const y = Number(row);
    if (y < 0 || y >= height) continue;
    for (const [x0, x1] of ranges) mask.fill(1, y * width + Math.max(0, x0), y * width + Math.min(width, x1));
  }
  return mask;
}

function makeMask(args) {
  const out = args[0];
  const dirs = [];
  let radius = 4, window = 0;
  for (let index = 1; index < args.length; index++) {
    if (args[index] === "--dilate") radius = Number(args[++index]);
    else if (args[index] === "--window") window = Number(args[++index]);
    else dirs.push(args[index]);
  }
  if (!out || dirs.length < 2 || !Number.isInteger(radius) || radius < 0 || !Number.isInteger(window) || window < 0) {
    usage("--make-mask needs an output file and at least two runs");
  }
  const names = [...new Set(dirs.flatMap(frames))].sort();
  if (!names.length) usage("No relics-*-gif-*.png frames in the given runs");

  // Pixels that differ between the runs, frame by frame.
  const differing = new Map();
  let failed = false;
  for (const name of names) {
    const missing = dirs.filter((dir) => !existsSync(join(dir, name)));
    if (missing.length) {
      console.log(`${name}: MISSING in ${missing.join(", ")}`);
      failed = true;
      continue;
    }
    const images = dirs.map((dir) => read(join(dir, name)));
    const { width, height } = images[0];
    if (images.some((image) => image.width !== width || image.height !== height)) {
      console.log(`${name}: SIZE differs between runs (${images.map((image) => `${image.width}x${image.height}`).join(", ")})`);
      failed = true;
      continue;
    }
    const pixels = [];
    const a = images[0].data;
    for (let pixel = 0, offset = 0; pixel < width * height; pixel++, offset += 4) {
      for (let run = 1; run < images.length; run++) {
        const b = images[run].data;
        if (a[offset] !== b[offset] || a[offset + 1] !== b[offset + 1] || a[offset + 2] !== b[offset + 2] || a[offset + 3] !== b[offset + 3]) {
          pixels.push(pixel);
          break;
        }
      }
    }
    differing.set(name, { width, height, pixels });
  }
  if (failed) {
    console.log("Mask not written: the runs do not have the same frames");
    process.exit(1);
  }

  // Each frame's mask: its own differences and those of its neighbours in the sequence, dilated.
  const sequences = new Map();
  for (const name of names) {
    const key = name.replace(/-\d+\.png$/, "");
    if (!sequences.has(key)) sequences.set(key, []);
    sequences.get(key).push(name);
  }
  const result = { version: 1, dilate: radius, window, runs: dirs.length, frames: {} };
  let masked = 0, touched = 0;
  for (const sequence of sequences.values()) {
    sequence.forEach((name, position) => {
      const { width, height, pixels } = differing.get(name);
      const union = new Uint8Array(width * height);
      for (let other = Math.max(0, position - window); other <= Math.min(sequence.length - 1, position + window); other++) {
        const neighbour = differing.get(sequence[other]);
        if (neighbour.width !== width || neighbour.height !== height) continue;
        for (const pixel of neighbour.pixels) union[pixel] = 1;
      }
      const { rows, count } = toRanges(dilate(union, width, height, radius), width, height);
      result.frames[name] = { width, height, differing: pixels.length, masked: count, rows };
      masked += count;
      if (count) touched++;
      console.log(`${name}: ${pixels.length} pixels differ between runs, ${count} masked`);
    });
  }
  writeFileSync(out, JSON.stringify(result));
  console.log(`Mask of ${names.length} frames from ${dirs.length} runs (dilated ${radius} px, window ${window}): ${touched} frames masked, `
      + `${masked} pixels in all -> ${out}`);
}

function compare(args) {
  let baseline, candidate, maskFile;
  for (let index = 0; index < args.length; index++) {
    if (args[index] === "--baseline") baseline = args[++index];
    else if (args[index] === "--candidate") candidate = args[++index];
    else if (args[index] === "--mask") maskFile = args[++index];
    else usage(`Unknown argument ${args[index]}`);
  }
  if (!baseline || !candidate) usage("--baseline and --candidate are required");
  const mask = maskFile ? JSON.parse(readFileSync(maskFile, "utf8")) : { frames: {} };
  const expected = frames(baseline), actual = new Set(frames(candidate));
  if (!expected.length) usage(`No relics-*-gif-*.png frames in ${baseline}`);
  let failures = 0, differing = 0, forgiven = 0;
  for (const name of expected) {
    if (!actual.has(name)) {
      console.log(`${name}: MISSING`);
      failures++;
      continue;
    }
    actual.delete(name);
    const a = read(join(baseline, name)), b = read(join(candidate, name));
    if (a.width !== b.width || a.height !== b.height) {
      console.log(`${name}: SIZE ${b.width}x${b.height}, baseline ${a.width}x${a.height}`);
      failures++;
      continue;
    }
    const entry = mask.frames[name];
    const allowed = entry && entry.width === a.width && entry.height === a.height ? fromRanges(entry, a.width, a.height) : null;
    let total = 0, masked = 0;
    for (let pixel = 0, offset = 0; pixel < a.width * a.height; pixel++, offset += 4) {
      if (a.data[offset] !== b.data[offset] || a.data[offset + 1] !== b.data[offset + 1] || a.data[offset + 2] !== b.data[offset + 2]
          || a.data[offset + 3] !== b.data[offset + 3]) {
        total++;
        if (allowed && allowed[pixel]) masked++;
      }
    }
    const unmasked = total - masked;
    differing += unmasked;
    forgiven += masked;
    if (unmasked) failures++;
    console.log(`${name}: ${unmasked} differing pixels outside the mask, ${masked} inside${unmasked ? "  FAIL" : ""}`);
  }
  for (const name of [...actual].sort()) {
    console.log(`${name}: EXTRA frame, not in the baseline`);
    failures++;
  }
  console.log(`${expected.length} baseline frames: ${failures} failing, ${differing} differing pixels outside the mask, ${forgiven} inside`);
  process.exit(failures ? 1 : 0);
}

const [mode, ...rest] = process.argv.slice(2);
if (mode === "--make-mask") makeMask(rest);
else if (mode) compare(process.argv.slice(2));
else usage();
