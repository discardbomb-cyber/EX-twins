#!/usr/bin/env node
// Assembles captured gallery frames into the README showcase GIF.
//
//   cd tools && npm install
//   node build_showcase_gif.mjs --out ../docs/images/ex-twins-showcase.gif \
//        --segment "../run-feature-gif/screenshots/relics-shield-gif-*.png:2:80" \
//        --segment "../run-feature-gif/screenshots/relics-hive-gif-*.png:1:64"
//
// Each segment is "glob:step:delayMs": every step-th frame is kept and shown for delayMs. Frames are
// cropped to the gallery's working area, scaled down, quantized to one palette per segment, and
// every pixel that did not change since the previous frame is written as transparent, so the
// mostly dark, mostly still galleries compress to a few megabytes.
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import gifenc from "gifenc";
import pngjs from "pngjs";

const { GIFEncoder, quantize, applyPalette } = gifenc;
const { PNG } = pngjs;

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : fallback;
};
const out = option("--out", "../docs/images/ex-twins-showcase.gif");
const width = Number(option("--width", 720));
const top = Number(option("--top", .06)), bottom = Number(option("--bottom", .9));
let fixedHeight = 0;
const segments = args.flatMap((arg, i) => arg === "--segment" ? [args[i + 1]] : []).map(spec => {
  const [pattern, step = "1", delay = "80"] = spec.split(/:(?=\d+(?::\d+)?$)/).flatMap(part => part.split(":"));
  return { pattern, step: Number(step), delay: Number(delay) };
});
if (!segments.length) {
  console.error("Give at least one --segment glob:step:delayMs");
  process.exit(1);
}

function frames(pattern) {
  const dir = dirname(pattern), glob = basename(pattern);
  const regex = new RegExp("^" + glob.replace(/[.+^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*") + "$");
  return readdirSync(dir).filter(name => regex.test(name)).sort().map(name => join(dir, name));
}

/** Crops to the gallery's working band and box-filters down to the output width. */
function load(file) {
  const png = PNG.sync.read(readFileSync(file));
  const y0 = Math.round(png.height * top), y1 = Math.round(png.height * bottom);
  const scale = png.width / width;
  // The first frame fixes the height; later frames (a resized window) are fitted to it.
  if (!fixedHeight) fixedHeight = Math.round((y1 - y0) / scale);
  const height = fixedHeight, scaleY = (y1 - y0) / height;
  const rgba = new Uint8Array(width * height * 4);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const sx0 = Math.floor(x * scale), sx1 = Math.max(sx0 + 1, Math.floor((x + 1) * scale));
    const sy0 = y0 + Math.floor(y * scaleY), sy1 = Math.max(sy0 + 1, y0 + Math.floor((y + 1) * scaleY));
    let r = 0, g = 0, b = 0, n = 0;
    for (let sy = sy0; sy < sy1; sy++) for (let sx = sx0; sx < sx1; sx++) {
      const i = (sy * png.width + sx) * 4;
      r += png.data[i]; g += png.data[i + 1]; b += png.data[i + 2]; n++;
    }
    const o = (y * width + x) * 4;
    rgba[o] = r / n; rgba[o + 1] = g / n; rgba[o + 2] = b / n; rgba[o + 3] = 255;
  }
  return { rgba, height };
}

const gif = GIFEncoder();
let written = 0, previous = null;
for (const segment of segments) {
  const files = frames(segment.pattern).filter((_, i) => i % segment.step === 0);
  if (!files.length) { console.error(`No frames for ${segment.pattern}`); process.exit(1); }
  const loaded = files.map(load);
  // One palette per segment from a spread of its frames; index 255 is kept for "unchanged".
  const sample = loaded.filter((_, i) => i % Math.max(1, Math.floor(loaded.length / 8)) === 0);
  const pool = new Uint8Array(sample.reduce((sum, f) => sum + f.rgba.length, 0));
  let offset = 0;
  for (const frame of sample) { pool.set(frame.rgba, offset); offset += frame.rgba.length; }
  const palette = quantize(pool, 255);
  while (palette.length < 255) palette.push([0, 0, 0]);
  palette.push([0, 0, 0]);
  previous = null;
  for (const frame of loaded) {
    const index = applyPalette(frame.rgba, palette.slice(0, 255));
    const pixels = new Uint8Array(index);
    if (previous && previous.length === pixels.length) {
      for (let i = 0; i < pixels.length; i++) if (pixels[i] === previous[i]) pixels[i] = 255;
    }
    gif.writeFrame(previous ? pixels : index, width, frame.height, {
      palette, delay: segment.delay, transparent: previous !== null, transparentIndex: 255, dispose: 1,
    });
    previous = index;
    written++;
  }
}
gif.finish();
writeFileSync(out, gif.bytes());
console.log(`Wrote ${written} frames to ${out} (${(gif.bytes().length / 1048576).toFixed(2)} MB)`);
