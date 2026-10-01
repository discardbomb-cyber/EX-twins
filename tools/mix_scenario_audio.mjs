#!/usr/bin/env node
// Mixes the soundtrack of filmed world-scenario takes from the sound logs they wrote (WorldScenarios), and lays their
// frames out as one run for a video: every sound the client started, at the moment it started, as loud as it was at
// the camera (Minecraft's linear fall-off with distance) and from the side it came from.
//
//   cd tools && npm install
//   node mix_scenario_audio.mjs --shots ../run-scenario/screenshots --take juice-rf-droplet --take juice-rf-barrage \
//        --out ../work/video/rf.wav --frames ../work/video/rf-frames
//
// Each --take is a scene's name; its frames (scenario-<name>-NNN.png), their game times (.ticks) and its sounds
// (.sounds) are read from --shots. A sound that follows what makes it (a figure rushing past) is logged again every
// tick, and its loudness, pitch and place are followed through the mix; a looping one plays until it stopped. The
// takes follow one another; --frames receives their frames, linked and numbered
// on, with a seq.ticks file on one clock, for the video renderer. Minecraft's own sounds are read from the game's
// asset index (--assets, the Gradle cache's by default) and left out if it cannot be found.
import { existsSync, linkSync, copyFileSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { homedir } from "node:os";
import { fileURLToPath } from "node:url";
import { OggVorbisDecoder } from "@wasm-audio-decoders/ogg-vorbis";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const RATE = 48_000, TICK = 1 / 20;
/** How long the last frame of a take is held, in ticks, as the video renderer holds it. */
const LAST_HOLD = 4;

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : fallback;
};
const takes = args.flatMap((arg, i) => arg === "--take" ? [args[i + 1]] : []);
const shots = option("--shots", join(ROOT, "run-scenario/screenshots"));
const out = option("--out");
const framesOut = option("--frames");
const assets = option("--assets", join(homedir(), ".gradle/caches/neoformruntime/assets"));
if (!takes.length || !out) {
  console.error("Give --out file.wav and at least one --take name");
  process.exit(1);
}

/** Minecraft's sound files by their resource path (minecraft/sounds/...), from the asset index, if it is there. */
const vanilla = (() => {
  const index = join(assets, "indexes/17.json");
  if (!existsSync(index)) return null;
  return JSON.parse(readFileSync(index, "utf8")).objects;
})();

function soundFile(path) {
  const [namespace, rest] = path.split(":");
  if (namespace === "relics_addon") return join(ROOT, "src/main/resources/assets/relics_addon", rest);
  const object = vanilla?.[`${namespace}/${rest}`];
  return object ? join(assets, "objects", object.hash.slice(0, 2), object.hash) : null;
}

const decoder = new OggVorbisDecoder();
await decoder.ready;
const decoded = new Map();
async function pcm(file) {
  if (!decoded.has(file)) {
    await decoder.reset();
    const { channelData, sampleRate } = await decoder.decodeFile(new Uint8Array(readFileSync(file)));
    // Mono: what the sound engine plays positioned.
    const mono = new Float32Array(channelData[0].length);
    for (const channel of channelData) for (let i = 0; i < mono.length; i++) mono[i] += channel[i] / channelData.length;
    decoded.set(file, { data: mono, rate: sampleRate });
  }
  return decoded.get(file);
}

function ticks(name) {
  return readFileSync(join(shots, `scenario-${name}.ticks`), "utf8").trim().split(/\r?\n/).map(line => {
    const [frame, time] = line.split(",");
    return { frame: Number(frame), time: Number(time) };
  });
}

function sounds(name) {
  const file = join(shots, `scenario-${name}.sounds`);
  if (!existsSync(file)) return [];
  const started = [], ticks = new Map();
  for (const line of readFileSync(file, "utf8").trim().split(/\r?\n/).filter(Boolean)) {
    const p = line.split(",");
    if (p[0] === "~") {
      // A ticking sound as it stood this tick: time, id, volume, pitch, where, whether it has stopped.
      const id = p[2];
      if (!ticks.has(id)) ticks.set(id, []);
      ticks.get(id).push({ time: Number(p[1]), volume: Number(p[3]), pitch: Number(p[4]), at: [Number(p[5]), Number(p[6]), Number(p[7])], stopped: p[8] === "true",
        path: p[9], reach: Number(p[10]), relative: p[11] === "true", attenuation: p[12], ear: [Number(p[13]), Number(p[14]), Number(p[15])], yaw: Number(p[16]),
        looping: p[17] === "true" });
      continue;
    }
    started.push({
      time: Number(p[0]), path: p[1], volume: Number(p[2]), pitch: Number(p[3]), reach: Number(p[4]),
      at: [Number(p[5]), Number(p[6]), Number(p[7])], relative: p[8] === "true", attenuation: p[9],
      ear: [Number(p[10]), Number(p[11]), Number(p[12])], yaw: Number(p[13]), id: p[15], looping: p[16] === "true",
    });
  }
  for (const sound of started) sound.ticks = (sound.id && ticks.get(sound.id) || []).filter(tick => tick.time >= Math.floor(sound.time));
  // A sound that started before the take (a construct's hum begun while the scene was set up) is played from its ticks alone.
  const known = new Set(started.map(sound => sound.id));
  for (const [id, course] of ticks) {
    const first = course[0];
    if (known.has(id) || !first.path) continue;
    started.push({ time: first.time, path: first.path, volume: first.volume, pitch: first.pitch, reach: first.reach, at: first.at, relative: first.relative,
      attenuation: first.attenuation, ear: first.ear, yaw: first.yaw, id, looping: first.looping, ticks: course.slice(1) });
  }
  return started;
}

/** How loud a sound plays at the camera, and how much of it each ear gets: volume, linear fall-off with distance, side. */
function heard(sound, volume, at) {
  const offset = sound.relative ? at : at.map((v, k) => v - sound.ear[k]);
  const distance = Math.hypot(...offset);
  const reach = Math.max(volume, 1) * sound.reach;
  const fall = sound.attenuation === "LINEAR" ? Math.max(0, 1 - distance / reach) : 1;
  const gain = Math.min(1, Math.max(0, volume)) * fall;
  const yaw = sound.yaw * Math.PI / 180, flat = Math.hypot(offset[0], offset[2]);
  const across = flat < 1e-6 ? 0 : (offset[0] * -Math.cos(yaw) + offset[2] * -Math.sin(yaw)) / flat;
  const angle = (across * .8 + 1) * Math.PI / 4;
  return [gain * Math.cos(angle) * Math.SQRT2, gain * Math.sin(angle) * Math.SQRT2];
}

// Lay the takes out one after another: where each starts in the film, and how long it lasts.
let start = 0;
const plan = takes.map(name => {
  const frames = ticks(name);
  const length = (frames.at(-1).time - frames[0].time + LAST_HOLD) * TICK;
  const take = { name, frames, sounds: sounds(name), start, length };
  start += length;
  return take;
});
const total = Math.ceil((start + 1.5) * RATE);
const left = new Float64Array(total), right = new Float64Array(total);
let placed = 0, missing = new Set();
for (const take of plan) {
  const first = take.frames[0].time;
  for (const sound of take.sounds) {
    const file = soundFile(sound.path);
    if (!file || !existsSync(file)) { missing.add(sound.path); continue; }
    const { data, rate } = await pcm(file);
    // Its course: as it started, then (for a ticking sound) as it stood every tick after, until it stopped.
    const course = [{ time: sound.time, volume: sound.volume, pitch: sound.pitch, at: sound.at }, ...sound.ticks.filter(tick => !tick.stopped)];
    const points = course.map(point => ({ time: point.time, ears: heard(sound, point.volume, point.at), step: rate / RATE * Math.max(.05, point.pitch) }));
    const ticking = sound.ticks.length > 0;
    // A ticking sound ends a tick after it was last seen; a plain one plays out.
    const ends = ticking ? points.at(-1).time + 1 : Infinity;
    if (Math.max(...points.map(point => Math.max(...point.ears))) <= .001) continue;
    const at = Math.round((take.start + (sound.time - first) * TICK) * RATE);
    let source = 0, next = 1;
    for (let i = 0; at + i < total; i++) {
      const time = sound.time + i / RATE / TICK;
      if (time >= ends) break;
      if (!sound.looping && source >= data.length - 1) break;
      while (next < points.length && points[next].time <= time) next++;
      const a = points[next - 1], b = points[Math.min(next, points.length - 1)];
      const u = b === a ? 0 : Math.min(1, (time - a.time) / (b.time - a.time));
      // A short fade where a ticking sound ends, so it never clicks off.
      const tail = ticking ? Math.min(1, (ends - time) / TICK / .4) : 1;
      const gainLeft = (a.ears[0] + (b.ears[0] - a.ears[0]) * u) * tail, gainRight = (a.ears[1] + (b.ears[1] - a.ears[1]) * u) * tail;
      if (at + i >= 0) {
        const wrapped = sound.looping ? source % (data.length - 1) : source;
        const k = Math.floor(wrapped), f = wrapped - k, value = data[k] * (1 - f) + data[k + 1] * f;
        left[at + i] += value * gainLeft;
        right[at + i] += value * gainRight;
      }
      source += a.step + (b.step - a.step) * u;
    }
    placed++;
  }
}
// Soft-limit what piles up, then bring the whole up to just under full scale.
let peak = 0;
for (let i = 0; i < total; i++) {
  left[i] = Math.tanh(left[i] * 1.2);
  right[i] = Math.tanh(right[i] * 1.2);
  peak = Math.max(peak, Math.abs(left[i]), Math.abs(right[i]));
}
const scale = peak > 0 ? .9 / peak : 1;
const data = Buffer.alloc(total * 4);
for (let i = 0; i < total; i++) {
  data.writeInt16LE(Math.round(Math.max(-1, Math.min(1, left[i] * scale)) * 32767), i * 4);
  data.writeInt16LE(Math.round(Math.max(-1, Math.min(1, right[i] * scale)) * 32767), i * 4 + 2);
}
const header = Buffer.alloc(44);
header.write("RIFF", 0); header.writeUInt32LE(36 + data.length, 4); header.write("WAVEfmt ", 8);
header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(2, 22);
header.writeUInt32LE(RATE, 24); header.writeUInt32LE(RATE * 4, 28); header.writeUInt16LE(4, 32); header.writeUInt16LE(16, 34);
header.write("data", 36); header.writeUInt32LE(data.length, 40);
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, Buffer.concat([header, data]));
decoder.free();
console.log(`Mixed ${placed} sounds over ${start.toFixed(1)} s of ${takes.length} take(s) into ${out}`
  + (missing.size ? `; left out ${missing.size} sound(s) with no file: ${[...missing].slice(0, 6).join(" ")}` : ""));

if (framesOut) {
  // The frames of every take in a row, on one clock (in ticks from the first take's first frame), for render.ps1.
  rmSync(framesOut, { recursive: true, force: true });
  mkdirSync(framesOut, { recursive: true });
  const lines = [];
  let index = 0;
  for (const take of plan) {
    const first = take.frames[0].time;
    for (const { frame, time } of take.frames) {
      const source = join(shots, `scenario-${take.name}-${String(frame).padStart(3, "0")}.png`);
      if (!existsSync(source)) continue;
      const target = join(framesOut, `seq-${String(index).padStart(4, "0")}.png`);
      try { linkSync(source, target); } catch { copyFileSync(source, target); }
      lines.push(`${index},${(take.start / TICK + time - first).toFixed(3)}`);
      index++;
    }
  }
  writeFileSync(join(framesOut, "seq.ticks"), lines.join("\n") + "\n");
  console.log(`Laid out ${index} frames in ${framesOut}`);
}
