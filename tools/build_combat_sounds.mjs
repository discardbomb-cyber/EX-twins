#!/usr/bin/env node
// Deterministic, original combat and console sounds for EX-twins, encoded as mono Ogg Vorbis.
//
// Everything is synthesized here (FM voices, filtered noise, chirps, short reverb); nothing is
// sampled from other works. Encoding uses a WASM Vorbis encoder, so no ffmpeg/oggenc/Python is
// needed:  cd tools && npm install && node build_combat_sounds.mjs [--validate] [--wav-only] [--only=name,name]
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createOggEncoder } from "wasm-media-encoders";
import { OggVorbisDecoder } from "@wasm-audio-decoders/ogg-vorbis";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const ASSETS = join(ROOT, "src/main/resources/assets/relics_addon");
const OUT = join(ASSETS, "sounds/combat");
const WAV_OUT = join(ROOT, "work/sound-preview");
const RATE = 48_000;
const TAU = Math.PI * 2;

// name, family, seconds. Names must match sounds.json (relics_addon:combat/<name>).
const SPECS = [
  ["rf_attack_1", "rf_attack", .26], ["rf_attack_2", "rf_attack", .30],
  ["rf_impact_1", "rf_impact", .22], ["rf_impact_2", "rf_impact", .26],
  ["mana_attack_1", "mana_attack", .46], ["mana_attack_2", "mana_attack", .52],
  ["mana_impact_1", "mana_impact", .42], ["mana_impact_2", "mana_impact", .48],
  ["twins_lightning_attack_1", "twins_lightning", .34], ["twins_lightning_attack_2", "twins_lightning", .38],
  ["twins_bolt_attack_1", "twins_bolt", .44], ["twins_bolt_attack_2", "twins_bolt", .50],
  ["twins_lightning_impact_1", "twins_lightning_hit", .28], ["twins_lightning_impact_2", "twins_lightning_hit", .32],
  ["twins_bolt_impact_1", "twins_bolt_hit", .38], ["twins_bolt_impact_2", "twins_bolt_hit", .42],
  ["rf_summon", "rf_summon", .70], ["rf_dismiss", "rf_dismiss", .45],
  ["mana_summon", "mana_summon", .85], ["mana_dismiss", "mana_dismiss", .55],
  ["twins_summon", "twins_summon", .95], ["twins_dismiss", "twins_dismiss", .60],
  ["shield_rf_absorb_1", "shield_absorb:rf", .22], ["shield_rf_absorb_2", "shield_absorb:rf", .26],
  ["shield_rf_cell_break", "shield_break:rf", .45], ["shield_rf_collapse", "shield_collapse:rf", 1.1],
  ["shield_mana_absorb_1", "shield_absorb:mana", .32], ["shield_mana_absorb_2", "shield_absorb:mana", .36],
  ["shield_mana_cell_break", "shield_break:mana", .55], ["shield_mana_collapse", "shield_collapse:mana", 1.25],
  ["shield_twins_absorb_1", "shield_absorb:twins", .30], ["shield_twins_absorb_2", "shield_absorb:twins", .34],
  ["shield_twins_cell_break", "shield_break:twins", .52], ["shield_twins_collapse", "shield_collapse:twins", 1.3],
  ["shield_mana_ripple", "shield_ripple:mana", .70], ["shield_twins_ripple", "shield_ripple:twins", .75],
  ["shield_rf_strike", "shield_strike:rf", .34], ["shield_mana_strike", "shield_strike:mana", .46],
  ["shield_twins_strike", "shield_strike:twins", .52],
  ["hive_rf_tesseract", "swarm_strike:rf", .70], ["hive_mana_droplet", "swarm_strike:mana", .75], ["hive_twins_pulsar", "swarm_strike:twins", .95],
  ["hive_rf_charge_fire", "charge_fire:rf", .45], ["hive_mana_charge_fire", "charge_fire:mana", .5], ["hive_twins_charge_fire", "charge_fire:twins", .55],
  ["hive_rf_lightning_blast", "lightning_blast:rf", 1.0], ["hive_mana_lightning_blast", "lightning_blast:mana", 1.05],
  ["hive_twins_lightning_blast", "lightning_blast:twins", 1.2],
  ["hive_rf_seal", "containment:rf", .8], ["hive_mana_ward", "containment:mana", 1.1], ["hive_twins_rift", "containment:twins", 1.3],
  ["hive_ward_reflect", "ward_reflect", .45],
  ["ui_toggle", "ui_toggle", .20], ["ui_upgrade", "ui_upgrade", .55],
];

// Base pitch per family: RF is metallic and bright, Mana glassy and high, Twins dark and low.
const PALETTE = {
  rf: { pitch: 880, ratio: 1.414, index: 2.6 },
  mana: { pitch: 1318, ratio: 3.5, index: 1.4 },
  twins: { pitch: 330, ratio: 1.5, index: 3.2 },
};

function rng(name) {
  let state = createHash("sha256").update(name).digest().readUInt32LE(0) || 1;
  return () => {
    state |= 0; state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

class Voice {
  constructor(seconds, name) {
    this.n = Math.max(1, Math.round(seconds * RATE));
    this.out = new Float64Array(this.n);
    this.random = rng(name);
  }
  gauss() { return Math.sqrt(-2 * Math.log(this.random() + 1e-12)) * Math.cos(TAU * this.random()); }
  noise() { const b = new Float64Array(this.n); for (let i = 0; i < this.n; i++) b[i] = this.gauss(); return b; }
  add(buffer, gain = 1, offset = 0) {
    const start = Math.round(offset * RATE);
    for (let i = 0; i < buffer.length && start + i < this.n; i++) if (start + i >= 0) this.out[start + i] += buffer[i] * gain;
    return this;
  }
}

const env = (n, attack, decay, hold = 0) => {
  const b = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    const t = i / RATE;
    const a = Math.min(1, t / Math.max(attack, 1e-4));
    b[i] = a * (t < attack + hold ? 1 : Math.exp(-(t - attack - hold) / decay));
  }
  return b;
};
const mul = (a, b) => a.map((v, i) => v * (b[i] ?? 0));
// Phase-accumulating oscillator with a frequency function f(t); shape: sine | saw | square.
function osc(n, freq, shape = "sine", phase = 0) {
  const b = new Float64Array(n);
  let p = phase;
  for (let i = 0; i < n; i++) {
    const f = typeof freq === "function" ? freq(i / RATE, i / n) : freq;
    p += f / RATE;
    const x = p - Math.floor(p);
    b[i] = shape === "saw" ? 2 * x - 1 : shape === "square" ? (x < .5 ? 1 : -1) : Math.sin(TAU * x);
  }
  return b;
}
// Two-operator FM; the index envelope makes strikes bright at first and pure as they decay.
function fm(n, carrier, ratio, index, indexDecay = .12) {
  const b = new Float64Array(n);
  let pc = 0, pm = 0;
  for (let i = 0; i < n; i++) {
    const t = i / RATE;
    const f = typeof carrier === "function" ? carrier(t, i / n) : carrier;
    pm += f * ratio / RATE; pc += f / RATE;
    b[i] = Math.sin(TAU * pc + index * Math.exp(-t / indexDecay) * Math.sin(TAU * pm));
  }
  return b;
}
// Inharmonic bell/glass partials.
function bell(n, base, partials = [1, 2.76, 5.4, 8.93], decay = .25) {
  const b = new Float64Array(n);
  partials.forEach((ratio, k) => {
    const d = decay / (1 + k * .8);
    for (let i = 0; i < n; i++) { const t = i / RATE; b[i] += Math.sin(TAU * base * ratio * t) * Math.exp(-t / d) / (1 + k * .6); }
  });
  return b;
}
function lowpass(buffer, cutoff) {
  const b = new Float64Array(buffer.length); let y = 0;
  for (let i = 0; i < buffer.length; i++) {
    const c = typeof cutoff === "function" ? cutoff(i / RATE, i / buffer.length) : cutoff;
    const a = 1 - Math.exp(-TAU * c / RATE); y += a * (buffer[i] - y); b[i] = y;
  }
  return b;
}
const highpass = (buffer, cutoff) => { const low = lowpass(buffer, cutoff); return buffer.map((v, i) => v - low[i]); };
// Resonant state-variable bandpass for swept noise.
function bandpass(buffer, center, q = 4) {
  const b = new Float64Array(buffer.length); let low = 0, band = 0;
  for (let i = 0; i < buffer.length; i++) {
    const c = typeof center === "function" ? center(i / RATE, i / buffer.length) : center;
    const f = 2 * Math.sin(Math.PI * Math.min(c, RATE / 6) / RATE);
    low += f * band; const high = buffer[i] - low - band / q; band += f * high; b[i] = band;
  }
  return b;
}
// Poisson crackle: sparse, randomly signed clicks with tiny ring-outs.
function crackle(voice, density, decay = .0015) {
  const b = new Float64Array(voice.n);
  for (let i = 0; i < voice.n; i++) {
    if (voice.random() < density(i / RATE, i / voice.n) / RATE) {
      const amp = (voice.random() < .5 ? -1 : 1) * (.4 + voice.random() * .6);
      for (let k = 0; k < RATE * decay * 6 && i + k < voice.n; k++) b[i + k] += amp * Math.exp(-k / (RATE * decay));
    }
  }
  return b;
}
// Small Schroeder reverb for air around the magical families.
function reverb(buffer, mix = .25, size = 1) {
  const combs = [1557, 1617, 1491, 1422].map(d => Math.round(d * size));
  const wet = new Float64Array(buffer.length);
  for (const d of combs) {
    const line = new Float64Array(d); let idx = 0;
    for (let i = 0; i < buffer.length; i++) { const y = line[idx]; line[idx] = buffer[i] + y * .78; idx = (idx + 1) % d; wet[i] += y / combs.length; }
  }
  for (const d of [225, 556].map(v => Math.round(v * size))) {
    const line = new Float64Array(d); let idx = 0;
    for (let i = 0; i < wet.length; i++) { const x = wet[i], y = line[idx]; line[idx] = x + y * .5; wet[i] = y - x * .5; idx = (idx + 1) % d; }
  }
  return buffer.map((v, i) => v * (1 - mix) + wet[i] * mix);
}

function synthesize(name, family, seconds) {
  const v = new Voice(seconds, name);
  const n = v.n;
  const variant = /_2$/.test(name) ? 1 : 0;
  const detune = 1 + (variant ? .06 : 0) + (v.random() - .5) * .02;
  const [kind, flavor] = family.split(":");
  const p = PALETTE[flavor] ?? PALETTE[family.split("_")[0]] ?? PALETTE.rf;
  let space = 0;

  switch (kind) {
    case "rf_attack": {
      // Coil discharge: a falling FM zap over a buzzing square body and spitting crackle.
      v.add(mul(fm(n, t => 2600 * detune * Math.exp(-t * 14) + 260, 1.41, 4, .05), env(n, .002, .07)), .55);
      v.add(mul(lowpass(osc(n, 118 * detune, "square"), 2400), env(n, .004, .09)), .22);
      v.add(highpass(crackle(v, (t, x) => 2600 * (1 - x)), 1800), .5);
      break;
    }
    case "rf_impact": {
      v.add(mul(osc(n, t => 150 * Math.exp(-t * 18) + 48), env(n, .001, .06)), .8);
      v.add(mul(bandpass(v.noise(), t => 3200 * Math.exp(-t * 6) + 900, 3), env(n, .001, .05)), .35);
      v.add(highpass(crackle(v, (t, x) => 1800 * (1 - x) ** 2), 2500), .45);
      break;
    }
    case "mana_attack": {
      // Rising air swell with a glassy arpeggio riding on top.
      v.add(mul(bandpass(v.noise(), (t, x) => 500 + 3800 * x, 6), env(n, seconds * .45, .1)), .25);
      [1, 1.26, 1.5].forEach((step, k) => v.add(mul(fm(n, p.pitch * .5 * step * detune, 3.5, 1.1, .2), env(n, .004, .12)), .28, k * .045));
      space = .3;
      break;
    }
    case "mana_impact": {
      v.add(bell(n, p.pitch * .75 * detune, [1, 2.76, 5.4, 8.93], .22), .5);
      v.add(mul(bandpass(v.noise(), 5200, 2), env(n, .001, .03)), .3);
      v.add(mul(osc(n, t => 220 * Math.exp(-t * 9) + 80), env(n, .002, .07)), .35);
      space = .35;
      break;
    }
    case "twins_lightning": {
      // Violent branching crackle with a low bloom underneath.
      v.add(highpass(crackle(v, (t, x) => 9000 * Math.exp(-x * 3), .0009), 900), .7);
      v.add(mul(bandpass(v.noise(), t => 2400 * Math.exp(-t * 5) + 400, 2.5), env(n, .002, .08)), .35);
      v.add(mul(osc(n, t => 62 * detune + 20 * Math.exp(-t * 12)), env(n, .006, .16)), .5);
      space = .2;
      break;
    }
    case "twins_lightning_hit": {
      v.add(highpass(crackle(v, (t, x) => 6000 * (1 - x) ** 3, .0012), 1200), .6);
      v.add(mul(osc(n, t => 110 * Math.exp(-t * 14) + 40), env(n, .001, .09)), .75);
      space = .2;
      break;
    }
    case "twins_bolt": {
      // Dark detuned saws swept down, with a rune tremolo.
      const body = osc(n, t => (180 - 90 * t / seconds) * detune, "saw").map((x, i) => x + osc(n, t => (182 - 92 * t / seconds) * detune, "saw")[i]);
      const trem = osc(n, 13).map(x => .65 + .35 * x);
      v.add(mul(mul(lowpass(body, (t, x) => 2600 - 1800 * x), trem), env(n, .03, .18)), .32);
      v.add(mul(fm(n, 440 * detune, 1.5, 2.4, .12), env(n, .01, .1)), .2);
      space = .35;
      break;
    }
    case "twins_bolt_hit": {
      v.add(bell(n, 196 * detune, [1, 2.4, 3.9, 6.2], .2), .5);
      v.add(mul(bandpass(v.noise(), t => 1500 * Math.exp(-t * 7) + 300, 3), env(n, .002, .07)), .4);
      space = .35;
      break;
    }
    case "rf_summon": case "mana_summon": case "twins_summon":
    case "rf_dismiss": case "mana_dismiss": case "twins_dismiss": {
      // Hive deploy is a rising charge with a closing chord; recall is the reverse.
      const up = kind.endsWith("summon");
      const base = p.pitch * (flavor === "twins" || kind.startsWith("twins") ? 1 : .5);
      const glide = (t, x) => base * (up ? .5 + .5 * x : 1 - .5 * x);
      v.add(mul(fm(n, glide, p.ratio, p.index, .4), env(n, up ? seconds * .7 : .01, up ? .12 : seconds * .35)), .35);
      v.add(mul(bandpass(v.noise(), (t, x) => 300 + 3000 * (up ? x : 1 - x), 5), env(n, up ? seconds * .6 : .01, .2)), .22);
      if (up) [1, 1.25, 1.5].forEach(step => v.add(mul(osc(n - Math.round(seconds * .62 * RATE), base * step), env(n, .01, .16)), .18, seconds * .62));
      if (kind.startsWith("rf")) v.add(highpass(crackle(v, (t, x) => 900 * (up ? x : 1 - x)), 2000), .3);
      space = kind.startsWith("rf") ? .15 : .35;
      break;
    }
    case "shield_absorb": {
      // Energy thud plus a resonant ping in the shield's material colour.
      v.add(mul(osc(n, t => 170 * Math.exp(-t * 20) + 55), env(n, .001, .05)), .6);
      v.add(mul(fm(n, p.pitch * detune, p.ratio, p.index, .04), env(n, .001, flavor === "twins" ? .12 : .08)), .38);
      if (flavor === "rf") v.add(highpass(crackle(v, (t, x) => 1500 * (1 - x) ** 2), 2500), .35);
      if (flavor === "twins") v.add(mul(fm(n, p.pitch * 1.005 * detune, p.ratio, p.index, .05), env(n, .001, .12)), .3);
      space = flavor === "rf" ? .1 : .3;
      break;
    }
    case "shield_break": {
      // Shatter: dozens of tiny bell grains scattered over the first third, over a crack.
      v.add(mul(bandpass(v.noise(), t => 4200 * Math.exp(-t * 4) + 700, 1.5), env(n, .001, .06)), .5);
      for (let g = 0; g < 28; g++) {
        const at = v.random() * seconds * .35, len = Math.round(.12 * RATE);
        v.add(bell(len, p.pitch * (1 + v.random() * 2.5), [1, 2.76, 5.4], .035), .08, at);
      }
      v.add(mul(osc(n, t => 120 * Math.exp(-t * 10) + 40), env(n, .001, .1)), .45);
      space = .3;
      break;
    }
    case "shield_collapse": {
      v.add(mul(fm(n, (t, x) => p.pitch * .5 * (1 - .8 * x), p.ratio, p.index, .6), env(n, .01, seconds * .4)), .35);
      v.add(mul(lowpass(v.noise(), (t, x) => 1800 * (1 - x) + 120), env(n, .02, seconds * .45)), .45);
      v.add(mul(osc(n, t => 70 * Math.exp(-t * 1.5) + 32), env(n, .01, seconds * .5)), .6);
      for (let g = 0; g < 18; g++) v.add(bell(Math.round(.15 * RATE), p.pitch * (.8 + v.random() * 2), [1, 2.76, 5.4], .05), .07, v.random() * seconds * .3);
      space = .4;
      break;
    }
    case "shield_ripple": {
      // Soft "woob": a vibrato sine and a breathing noise band travelling down, like a bent pane.
      const base = flavor === "mana" ? 520 : 240;
      const wob = (t, x) => base * (1.15 - .4 * x) * (1 + .06 * Math.sin(TAU * (9 - 5 * x) * t));
      v.add(mul(osc(n, wob), env(n, .05, seconds * .3)), .45);
      v.add(mul(bandpass(v.noise(), (t, x) => base * 4 * (1 - .6 * x), 8), env(n, .08, seconds * .25)), .3);
      if (flavor === "twins") v.add(mul(osc(n, (t, x) => base * .5 * (1.1 - .3 * x)), env(n, .06, seconds * .3)), .3);
      space = .45;
      break;
    }
    case "shield_strike": {
      // The shell hitting back: a concussive push, then the shield's own voice. RF snaps like a
      // discharge, Mana blooms like struck glass, Twins does both over a darker body.
      v.add(mul(osc(n, t => 96 * Math.exp(-t * 15) + 38), env(n, .001, .09)), .75);
      v.add(mul(bandpass(v.noise(), t => 2800 * Math.exp(-t * 9) + 320, 2.2), env(n, .002, .07)), .42);
      if (flavor !== "mana") {
        v.add(mul(fm(n, t => 3400 * Math.exp(-t * 20) + 260, 1.41, 3.6, .035), env(n, .001, .05)), .42);
        v.add(highpass(crackle(v, (t, x) => (flavor === "rf" ? 7500 : 5200) * (1 - x) ** 2, .0009), 1900), .55);
      }
      if (flavor !== "rf") {
        v.add(mul(fm(n, t => p.pitch * .5 * (1 + .45 * Math.exp(-t * 11)) * detune, p.ratio, p.index * .8, .07), env(n, .002, .14)), .32);
        v.add(bell(n, p.pitch * (flavor === "twins" ? .5 : .75) * detune, [1, 2.76, 5.4], .16), .22);
      }
      if (flavor === "twins") v.add(mul(osc(n, t => 58 * detune + 24 * Math.exp(-t * 10)), env(n, .004, .16)), .4);
      space = flavor === "rf" ? .12 : .32;
      break;
    }
    case "swarm_strike": {
      // A whole group hitting as one: RF a hyperspace sweep folding in on itself, Mana a heavy splash,
      // Twins a pulsar's beat: sharp broadband ticks at a steady rate over a low hum.
      v.add(mul(osc(n, t => 70 * Math.exp(-t * 7) + 34), env(n, .003, .22)), .8);
      if (flavor === "rf") {
        v.add(mul(fm(n, (t, x) => 180 + 1400 * x * (1 - x) * 4 * detune, 1.5, 3.2, .3), env(n, .01, .25)), .35);
        v.add(highpass(crackle(v, (t, x) => 5000 * (1 - x) ** 1.5, .001), 1800), .5);
      } else if (flavor === "mana") {
        v.add(mul(bandpass(v.noise(), t => 2600 * Math.exp(-t * 5) + 250, 1.8), env(n, .002, .18)), .6);
        for (let g = 0; g < 14; g++) v.add(bell(Math.round(.12 * RATE), p.pitch * (.6 + v.random() * 1.2), [1, 2.76], .05), .08, .03 + v.random() * seconds * .5);
      } else {
        const beat = 13;
        const ticks = v.noise().map((x, i) => { const t = i / RATE; const phase = (t * beat) % 1; return x * Math.exp(-phase * 55) * Math.exp(-t * 2.4); });
        v.add(highpass(ticks, 900), .9);
        v.add(mul(osc(n, t => 55 * detune), env(n, .02, seconds * .5)), .45);
      }
      space = flavor === "rf" ? .2 : .35;
      break;
    }
    case "charge_fire": {
      // Release of a charged ball: a whine rushing up and a soft thump as it leaves.
      v.add(mul(fm(n, (t, x) => p.pitch * (.4 + .9 * x) * detune, p.ratio, p.index * .7, .5), env(n, seconds * .5, .06)), .35);
      v.add(mul(bandpass(v.noise(), (t, x) => 400 + 3200 * x, 4), env(n, seconds * .55, .05)), .3);
      v.add(mul(osc(n, t => 110 * Math.exp(-Math.max(0, t - seconds * .55) * 18) + 45), env(n, seconds * .55, .08, 0)), .5, 0);
      if (flavor !== "mana") v.add(highpass(crackle(v, (t, x) => 2500 * x, .001), 2000), .3);
      space = .25;
      break;
    }
    case "lightning_blast": {
      // The crack and roll of a lightning blast: a white crack, a rumble, sparks raining after.
      v.add(mul(highpass(v.noise(), 1500), env(n, .0008, .03)), .9);
      v.add(mul(lowpass(v.noise(), (t, x) => 900 * Math.exp(-t * 2.5) + 90), env(n, .004, seconds * .35)), .8);
      v.add(mul(osc(n, t => 48 * detune + 25 * Math.exp(-t * 8)), env(n, .002, seconds * .3)), .6);
      v.add(highpass(crackle(v, (t, x) => 3500 * Math.exp(-x * 3), .0012), 1500), .45);
      if (flavor === "mana") v.add(bell(n, p.pitch * .5 * detune, [1, 2.76, 5.4], .3), .25);
      if (flavor === "twins") v.add(mul(fm(n, t => 90 * detune, 1.5, 2.5, .6), env(n, .01, seconds * .4)), .3);
      space = .35;
      break;
    }
    case "containment": {
      // A construct holding its target: RF buzzing seals, the Mana ward's chord, the Twins rift's swell.
      if (flavor === "rf") {
        v.add(mul(lowpass(osc(n, 120 * detune, "square"), 1800), env(n, .02, seconds * .4)), .3);
        v.add(highpass(crackle(v, (t, x) => 1800 * Math.sin(Math.PI * x), .0015), 1800), .6);
      } else if (flavor === "mana") {
        [1, 1.25, 1.5, 2].forEach(step => v.add(mul(osc(n, t => 330 * step * detune * (1 + .004 * Math.sin(t * 30))), env(n, .15, seconds * .4)), .14));
        v.add(mul(bandpass(v.noise(), 5000, 10), env(n, .2, seconds * .3)), .12);
      } else {
        v.add(mul(osc(n, (t, x) => 40 + 30 * x), env(n, seconds * .6, .25)), .6);
        v.add(mul(bandpass(v.noise(), (t, x) => 200 + 1600 * (1 - x), 3), env(n, seconds * .7, .2)), .4);
      }
      space = .4;
      break;
    }
    case "ward_reflect": {
      v.add(bell(n, 1500 * detune, [1, 2.76, 5.4, 8.93], .2), .5);
      v.add(mul(bandpass(v.noise(), (t, x) => 6000 - 4500 * x, 5), env(n, .002, .12)), .3);
      v.add(mul(osc(n, t => 300 * Math.exp(-t * 6) + 120), env(n, .002, .1)), .35);
      space = .35;
      break;
    }
    case "ui_toggle": {
      v.add(mul(osc(n, 660), env(n, .002, .03)), .4);
      v.add(mul(osc(n - Math.round(.07 * RATE), 990), env(n, .002, .05)), .4, .07);
      v.add(mul(highpass(v.noise(), 4000), env(n, .0005, .006)), .25);
      break;
    }
    case "ui_upgrade": {
      [1, 1.26, 1.5, 2].forEach((step, k) => v.add(mul(fm(n, 523 * step, 2, 1.2, .08), env(n, .003, .12)), .22, k * .07));
      v.add(mul(bandpass(v.noise(), (t, x) => 2000 + 6000 * x, 6), env(n, seconds * .5, .1)), .12);
      space = .3;
      break;
    }
    default:
      throw new Error(`Unhandled family ${family}`);
  }

  let out = space > 0 ? reverb(v.out, space, flavor === "twins" ? 1.2 : 1) : v.out;
  // Zero DC, normalise below full scale, and force click-free edges.
  const mean = out.reduce((a, b) => a + b, 0) / out.length;
  out = out.map(x => x - mean);
  const peak = out.reduce((a, b) => Math.max(a, Math.abs(b)), 0);
  const gain = peak ? .8 / peak : 0;
  const fade = Math.min(Math.round(RATE * .012), Math.floor(n / 3));
  return Float32Array.from(out, (x, i) => x * gain * Math.min(1, i / fade, (n - 1 - i) / fade));
}

function wav(samples) {
  const data = Buffer.alloc(samples.length * 2);
  samples.forEach((x, i) => data.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(x * 32767))), i * 2));
  const header = Buffer.alloc(44);
  header.write("RIFF", 0); header.writeUInt32LE(36 + data.length, 4); header.write("WAVEfmt ", 8);
  header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(1, 22);
  header.writeUInt32LE(RATE, 24); header.writeUInt32LE(RATE * 2, 28); header.writeUInt16LE(2, 32); header.writeUInt16LE(16, 34);
  header.write("data", 36); header.writeUInt32LE(data.length, 40);
  return Buffer.concat([header, data]);
}

async function encode(samples) {
  const encoder = await createOggEncoder();
  encoder.configure({ channels: 1, sampleRate: RATE, vbrQuality: 5 });
  const chunks = [];
  // The encoder reuses its output buffer, so every chunk is copied.
  for (let i = 0; i < samples.length; i += 8192) chunks.push(Buffer.from(encoder.encode([samples.subarray(i, i + 8192)])));
  chunks.push(Buffer.from(encoder.finalize()));
  return Buffer.concat(chunks);
}

async function validate() {
  const decoder = new OggVorbisDecoder();
  await decoder.ready;
  const sounds = JSON.parse(readFileSync(join(ASSETS, "sounds.json"), "utf8"));
  const referenced = new Set(Object.values(sounds).flatMap(e => e.sounds.map(s => (typeof s === "string" ? s : s.name).split(":")[1])));
  const problems = [];
  for (const path of referenced) {
    const file = join(ASSETS, "sounds", `${path}.ogg`);
    if (!existsSync(file)) { problems.push(`missing ${path}.ogg`); continue; }
    decoder.reset && await decoder.reset();
    const { channelData, sampleRate, errors } = await decoder.decodeFile(new Uint8Array(readFileSync(file)));
    const pcm = channelData[0];
    const peak = pcm.reduce((a, b) => Math.max(a, Math.abs(b)), 0);
    const dc = pcm.reduce((a, b) => a + b, 0) / pcm.length;
    if (errors?.length) problems.push(`${path}: decode errors`);
    if (channelData.length !== 1) problems.push(`${path}: ${channelData.length} channels (positional sounds must be mono)`);
    if (sampleRate !== RATE) problems.push(`${path}: ${sampleRate} Hz`);
    if (peak > .99 || peak < .2) problems.push(`${path}: peak ${peak.toFixed(3)}`);
    if (Math.abs(dc) > .01) problems.push(`${path}: dc ${dc.toFixed(4)}`);
  }
  const lang = ["en_us", "ru_ru"].map(l => JSON.parse(readFileSync(join(ASSETS, `lang/${l}.json`), "utf8")));
  for (const [event, entry] of Object.entries(sounds)) {
    if (entry.subtitle && lang.some(l => !l[entry.subtitle])) problems.push(`${event}: subtitle ${entry.subtitle} missing in lang`);
  }
  decoder.free();
  if (problems.length) { console.error(problems.join("\n")); process.exit(1); }
  console.log(`Validated ${referenced.size} sound files: mono ${RATE} Hz, bounded peak, no DC, subtitles present.`);
}

async function main() {
  const args = new Set(process.argv.slice(2));
  if (args.has("--validate")) return validate();
  const wavOnly = args.has("--wav-only");
  const only = [...args].find(arg => arg.startsWith("--only="))?.slice(7).split(",");
  mkdirSync(wavOnly ? WAV_OUT : OUT, { recursive: true });
  const specs = only ? SPECS.filter(([name]) => only.includes(name)) : SPECS;
  for (const [name, family, seconds] of specs) {
    const samples = synthesize(name, family, seconds);
    if (wavOnly) writeFileSync(join(WAV_OUT, `${name}.wav`), wav(samples));
    else writeFileSync(join(OUT, `${name}.ogg`), await encode(samples));
  }
  console.log(`Generated ${specs.length} ${wavOnly ? "WAV previews in work/sound-preview" : "Ogg Vorbis assets"}.`);
}

await main();
