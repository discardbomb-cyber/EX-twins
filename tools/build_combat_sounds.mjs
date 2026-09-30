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
  // Armageddon: the cannon charging (it lasts exactly until the shot), the shot, and the blast.
  ["hive_armageddon_charge", "armageddon_charge:twins", 60.0], ["hive_armageddon_fire", "armageddon_fire:twins", 1.2],
  ["hive_armageddon_blast", "armageddon_blast:twins", 30.0], ["hive_armageddon_shock", "armageddon_shock:twins", 4.0], ["hive_armageddon_devour", "armageddon_devour:twins", 3.0],
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
// A vast reverb: damped comb filters long enough to ring on for {@code seconds}, smeared by two allpasses.
function vast(buffer, mix, seconds) {
  const combs = [.0971, .1093, .1187, .1301, .1409, .1523].map(d => Math.round(d * RATE));
  const wet = new Float64Array(buffer.length);
  for (const d of combs) {
    const g = Math.pow(10, -3 * d / RATE / seconds), line = new Float64Array(d);
    let idx = 0, low = 0;
    for (let i = 0; i < buffer.length; i++) {
      const y = line[idx];
      low += .35 * (y - low);
      line[idx] = buffer[i] + low * g;
      idx = (idx + 1) % d;
      wet[i] += y / combs.length;
    }
  }
  for (const d of [347, 113].map(v => Math.round(v * RATE / 44100))) {
    const line = new Float64Array(d); let idx = 0;
    for (let i = 0; i < wet.length; i++) { const x = wet[i], y = line[idx]; line[idx] = x + y * .6; wet[i] = y - x * .6; idx = (idx + 1) % d; }
  }
  return buffer.map((v, i) => v * (1 - mix) + wet[i] * mix * 2.2);
}
// Pink noise (equal energy in every octave, like a real explosion or fire), after Paul Kellet's filter.
function pink(voice) {
  const b = new Float64Array(voice.n);
  let b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
  for (let i = 0; i < voice.n; i++) {
    const w = voice.gauss();
    b0 = .99886 * b0 + w * .0555179; b1 = .99332 * b1 + w * .0750759; b2 = .969 * b2 + w * .153852;
    b3 = .8665 * b3 + w * .3104856; b4 = .55 * b4 + w * .5329522; b5 = -.7616 * b5 - w * .016898;
    b[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * .5362) * .2;
    b6 = w * .115926;
  }
  return b;
}
// A slow random wobble round 1, about {@code rate} times a second, {@code depth} deep: the roiling of fire.
function wobble(voice, rate, depth) {
  const slow = lowpass(lowpass(voice.noise(), rate), rate);
  let peak = 0;
  for (const x of slow) peak = Math.max(peak, Math.abs(x));
  return slow.map(x => 1 + depth * x / (peak || 1));
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
    case "armageddon_charge": {
      // A minute of charging, in three stages as the rings light. First a deep, dense sub-bass drone that
      // makes the air tremble, monotonous and threatening. With the second ring, a metallic resonance and a
      // thin ringing overtone join it, like metal under colossal strain. From the third ring the peak: hum and
      // ring merge, the trembling quickens, and it breaks into electric crackle and a pulsing, piercing
      // whine that speeds up, until the shot leaves.
      const rings = [13, 24, 35, 46], metal = 24, peak = 35;
      const stage = (t, from) => Math.min(1, Math.max(0, (t - from) / 3));
      // The trembling: slow at first, faster and faster through the peak.
      const trembleRate = t => 3.5 + 12 * Math.max(0, (t - peak) / (seconds - peak)) ** 1.5;
      let phase = 0;
      const tremble = new Float64Array(n);
      for (let i = 0; i < n; i++) { const t = i / RATE; phase += trembleRate(t) / RATE; tremble[i] = 1 - (.25 + .2 * stage(t, peak)) * (.5 + .5 * Math.sin(TAU * phase)); }
      const drone = t => Math.min(1, t / 1.5) * (.85 + .15 * t / seconds);
      v.add(osc(n, 43.9).map((x, i) => x * drone(i / RATE) * tremble[i]), 1);
      v.add(osc(n, 87.8).map((x, i) => x * drone(i / RATE) * tremble[i]), .35);
      v.add(osc(n, t => 43.9 * (1 + .015 * Math.min(1, t / seconds))).map((x, i) => x * drone(i / RATE)), .45);
      v.add(lowpass(lowpass(v.noise(), 140), 140).map((x, i) => x * drone(i / RATE) * tremble[i]), 2.2);
      // The metal under strain: inharmonic partials of a vast plate, beating slowly, and a thin ringing wire.
      [[180, 1, .16], [180, 2.76, .1], [180, 5.4, .07], [180, 8.93, .045], [180, 13.34, .03]].forEach(([base, ratio, gain], k) => {
        const tone = osc(n, t => base * ratio * detune * (1 + .002 * Math.sin(TAU * (.13 + .05 * k) * t)));
        v.add(tone.map((x, i) => { const t = i / RATE; return x * stage(t, metal) * (.7 + .3 * Math.sin(TAU * .31 * t + k)) * (1 + .6 * stage(t, peak)); }), gain);
      });
      v.add(osc(n, t => 2890 * (1 + .004 * Math.sin(TAU * 5.5 * t))).map((x, i) => x * stage(i / RATE, metal) * (1 + stage(i / RATE, peak))), .035);
      // The peak: electric crackle thickening, and a piercing whine pulsing faster and faster.
      v.add(highpass(crackle(v, t => t < peak ? 20 : 300 + 4000 * ((t - peak) / (seconds - peak)) ** 1.5, .0012), 1800), .45);
      let pulse = 0;
      const whine = new Float64Array(n);
      for (let i = 0; i < n; i++) {
        const t = i / RATE, u = Math.max(0, (t - peak) / (seconds - peak));
        pulse += (6 + 26 * u * u) / RATE;
        whine[i] = Math.sin(TAU * (9500 + 1500 * u) * t) * stage(t, peak) * Math.pow(.5 + .5 * Math.sin(TAU * pulse), 3);
      }
      v.add(whine, .07);
      // A rising charge through the peak, surging in the last seconds.
      v.add(bandpass(v.noise(), t => 300 + 2500 * Math.max(0, (t - peak) / (seconds - peak)) ** 2, 2).map((x, i) => x * stage(i / RATE, peak) * (.3 + .7 * Math.max(0, (i / RATE - peak) / (seconds - peak)))), .5);
      // Each ring lands with a thoom.
      for (const at of rings) {
        const hit = new Float64Array(n);
        for (let i = Math.round(at * RATE); i < n; i++) {
          const t = i / RATE - at;
          hit[i] = Math.sin(TAU * (78 * t - 20 * t * t)) * Math.exp(-t / .6) + .35 * Math.sin(TAU * 660 * t * detune) * Math.exp(-t / .4);
        }
        v.add(hit, .8);
        v.add(mul(highpass(v.noise(), 3000), env(n, .001, .06)), .12, at);
      }
      const cut = new Float64Array(n);
      for (let i = 0; i < n; i++) { const t = i / RATE; cut[i] = t < seconds - .06 ? 1 : Math.max(0, (seconds - t) / .06); }
      v.out = mul(v.out, cut);
      space = .3;
      break;
    }
    case "armageddon_fire": {
      // The anomaly in flight: space itself squeezing and letting go with every beat, a heavy, suffocating
      // pulse, rising and quickening as it closes on its target.
      let phase = 0;
      const pumping = new Float64Array(n);
      for (let i = 0; i < n; i++) { const t = i / RATE; phase += (6 + 5 * t / seconds) / RATE; pumping[i] = Math.pow(.5 + .5 * Math.sin(TAU * phase - Math.PI / 2), 1.6); }
      const swell = t => Math.min(1, t / .05) * (.7 + .3 * t / seconds) * (t < seconds - .08 ? 1 : Math.max(0, (seconds - t) / .08));
      v.add(mul(osc(n, t => 26 + 60 * Math.exp(-t * 8)), env(n, .002, .35)), 1.2);
      v.add(osc(n, t => 42 + 16 * t / seconds).map((x, i) => x * pumping[i] * swell(i / RATE)), 1.1);
      v.add(lowpass(osc(n, t => 42 + 16 * t / seconds, "saw"), 500).map((x, i) => Math.tanh(2 * x) * pumping[i] * swell(i / RATE)), .4);
      v.add(bandpass(v.noise(), (t, x) => 250 + 1400 * pumping[Math.min(n - 1, Math.round(t * RATE))] + 1200 * t / seconds, 1.8).map((x, i) => x * swell(i / RATE) * (.3 + .7 * pumping[i])), 1.1);
      space = .15;
      break;
    }
    case "armageddon_blast": {
      // The supernova and the eruption. A deafening white blast that cuts everything off, then the stunned
      // quiet of the blinded: a muffled rumble and a ringing in the ears. The ball forms with a heavy, buckling
      // grind like a giant tin can crushed, deep bass hitting the chest, in a vast echo; it swells, holds, and
      // crushes back in. Then the eruption: the unbroken roar of energy pouring to the zenith, white noise and
      // a jet's howl, torn by dull, deep pops and swirls from the black spheres flickering in the beam, until
      // it narrows away.
      const stun = 2.5, ball = 2.5, hold = 10, crushed = 13, erupt = 12.75, fade = 26;
      // The white blast.
      v.add(mul(highpass(v.noise(), 30), env(n, .0008, .35)), 1.6);
      v.add(mul(osc(n, t => 24 + 90 * Math.exp(-t * 9)), env(n, .001, .6)), 1.4);
      // Stunned: a muffled rumble and a ringing in the ears, both fading.
      const muffled = t => t < stun + .3 ? Math.min(1, t / .3) * Math.exp(-t / 1.6) : 0;
      v.add(lowpass(lowpass(v.noise(), 260), 260).map((x, i) => x * muffled(i / RATE)), 1.6);
      v.add(osc(n, 3520).map((x, i) => { const t = i / RATE; return x * Math.min(1, t / .15) * Math.exp(-t / 1.2); }), .05);
      // The ball: a buckling metal grind with deep bass under it, growing as it swells and tightening as it is crushed back.
      const grind = t => t < ball ? 0 : t < hold ? Math.min(1, (t - ball) / .2) * (.6 + .4 * (t - ball) / (hold - ball)) : t < crushed - .03 ? 1 + .3 * (t - hold) / (crushed - hold) : Math.max(0, 1.3 * (crushed - t) / .03);
      let buckle = 0;
      const crumple = new Float64Array(n);
      for (let i = 0; i < n; i++) {
        const t = i / RATE, g = grind(t);
        if (g <= 0) continue;
        if (v.random() < (40 + 90 * (t > hold ? (t - hold) / (crushed - hold) : 0)) / RATE) {
          const f = 180 + 1600 * v.random() ** 2, decay = RATE * (.01 + .03 * v.random()), amp = (v.random() < .5 ? -1 : 1) * g;
          for (let k = 0; k < decay * 5 && i + k < n; k++) crumple[i + k] += amp * Math.sin(TAU * f * k / RATE) * Math.exp(-k / decay);
        }
        buckle += (52 + 30 * (t > hold ? (t - hold) / (crushed - hold) : 0) + 8 * Math.sin(TAU * .7 * t)) / RATE;
      }
      v.add(crumple, .35);
      const groan = osc(n, t => 52 + 30 * (t > hold ? Math.min(1, (t - hold) / (crushed - hold)) : 0) + 8 * Math.sin(TAU * .7 * t), "saw");
      v.add(bandpass(groan, t => 180 + 120 * Math.sin(TAU * .4 * t), 3).map((x, i) => Math.tanh(2 * x) * grind(i / RATE)), .9);
      v.add(osc(n, t => 34 + 6 * Math.sin(TAU * .25 * t)).map((x, i) => x * Math.min(1, grind(i / RATE))), .9);
      v.add(lowpass(highpass(pink(v), 60), 3000).map((x, i) => x * grind(i / RATE) * .5), .7);
      // The eruption: the roar of the beam, a jet's howl in it, and deep pops and swirls tearing through it.
      const roar = t => t < erupt ? 0 : t < fade ? Math.min(1, (t - erupt) / .3) : Math.max(0, 1 - (t - fade) / 2.5);
      v.add(lowpass(highpass(v.noise(), 80), 9000).map((x, i) => x * roar(i / RATE)), .55);
      v.add(lowpass(highpass(pink(v), 40), 1200).map((x, i) => x * roar(i / RATE)), 1.1);
      v.add(osc(n, t => 1850 * (1 + .01 * Math.sin(TAU * 3 * t))).map((x, i) => x * roar(i / RATE)), .05);
      v.add(osc(n, t => 925 * (1 + .01 * Math.sin(TAU * 3 * t + 1))).map((x, i) => x * roar(i / RATE)), .04);
      // Pops on the ticks the black spheres flicker up the beam (ArmageddonVisual.pop).
      for (let k = 0, at = erupt + .4; at < fade + 1.5; k++, at = erupt + .4 + .55 * k + .25 * Math.sin(2.3 * k)) {
        v.add(mul(osc(n, t => 50 + 70 * Math.exp(-t * 18)), env(n, .002, .09)), .8 * roar(at), at);
        if (v.random() < .5) v.add(mul(bandpass(v.noise(), t => 300 + 2500 * Math.min(1, t / .35), 2.5), env(n, .15, .2)), .35 * roar(at), at);
      }
      v.out = vast(v.out, .35, 5);
      space = 0;
      break;
    }
    case "armageddon_devour": {
      // Three seconds at the target. The white ball opens into a black hole with a deep, heavy whoomp, and its
      // containment collapses: a deafening metallic shriek and ringing like
      // titanium chains snapping under strain, the drones whistling away into the distance, the rings gone in
      // a whoosh, and for a moment a vacuum's silence. Then the singularity: the black hole crushes itself to
      // a point and devours the land with a dull crunch and suck, over a pulsar beating dry and sharp, faster
      // every second, until the burst cuts it dead.
      const snap = .9, hunger = 1.15;
      const collapse = t => t < snap - .06 ? 1 : Math.max(0, (snap - t) / .06);
      // The black hole opening.
      v.add(mul(osc(n, t => 24 + 70 * Math.exp(-t * 7)), env(n, .003, .5)), 1.5);
      v.add(mul(lowpass(v.noise(), t => 900 * Math.exp(-t * 5) + 90), env(n, .002, .35)), 1.1);
      // Chains snapping: metal shrieking and ringing, and hard metallic cracks one after another.
      v.add(mul(bandpass(v.noise(), t => 2600 + 1400 * Math.sin(TAU * 23 * t), 6), env(n, .002, .35)).map((x, i) => x * collapse(i / RATE)), 1.4);
      [[520, 1, .3], [520, 2.76, .2], [520, 5.4, .14], [520, 8.93, .09], [1310, 1, .12]].forEach(([base, ratio, gain]) => {
        v.add(mul(osc(n, base * ratio * detune), env(n, .002, .45)).map((x, i) => x * collapse(i / RATE)), gain);
      });
      for (const at of [0, .07, .16, .22, .31, .45]) {
        v.add(mul(highpass(v.noise(), 2500), env(n, .0005, .012)), .9, at);
        v.add(mul(bell(n, 900 + 700 * v.random(), [1, 2.76, 5.4], .08), env(n, .001, .15)), .25, at);
      }
      // The drones whistling away, each its own way, fading into the distance.
      for (let k = 0; k < 7; k++) {
        const start = .05 + .08 * k, top = 2600 + 900 * v.random();
        const whistle = osc(n, t => t < start ? top : top * Math.exp(-(t - start) * 1.4));
        v.add(whistle.map((x, i) => { const t = i / RATE - start; return t < 0 ? 0 : x * Math.min(1, t / .02) * Math.exp(-t / .3) * collapse(i / RATE); }), .06);
      }
      // The rings gone in a whoosh.
      v.add(bandpass(v.noise(), t => 400 + 3000 * Math.min(1, t / snap), 1.5).map((x, i) => { const t = i / RATE; return x * Math.min(1, t / .5) * collapse(t); }), .6);
      // A little room round the collapse, then the vacuum's silence, truly silent.
      v.out = reverb(v.out, .12, 1).map((x, i) => x * collapse(i / RATE));
      // The singularity: dull crunches and a sucking hiss, over a pulsar's dry beat speeding up.
      const eat = t => t < hunger ? 0 : Math.min(1, (t - hunger) / .1) * (t < seconds - .03 ? 1 : Math.max(0, (seconds - t) / .03));
      v.add(lowpass(crackle(v, t => t < hunger ? 0 : 14 + 10 * (t - hunger), .025), 400).map((x, i) => x * eat(i / RATE)), 1.3);
      v.add(bandpass(v.noise(), t => 300 + 2500 * Math.max(0, (t - hunger) / (seconds - hunger)) ** 1.5, 3).map((x, i) => x * eat(i / RATE) * .8), .8);
      let beat = 0, last = -1;
      const pulsar = new Float64Array(n);
      for (let i = 0; i < n; i++) {
        const t = i / RATE;
        if (t < hunger) continue;
        const u = (t - hunger) / (seconds - hunger);
        beat += 5 * Math.pow(9, u) / RATE;
        if (Math.floor(beat) !== last) {
          last = Math.floor(beat);
          for (let k = 0; k < RATE * .004 && i + k < n; k++) pulsar[i + k] += Math.sin(TAU * 3400 * k / RATE) * Math.exp(-k / (RATE * .0008)) + (v.random() - .5) * Math.exp(-k / (RATE * .0004));
        }
      }
      v.add(pulsar.map((x, i) => x * eat(i / RATE)), .9);
      v.add(osc(n, 55).map((x, i) => x * eat(i / RATE) * .5), .5);
      space = 0;
      break;
    }
    case "armageddon_shock": {
      // The shock wave reaching the listener: a rush of air, a boom felt more than heard, and the land
      // rumbling and rattling after it.
      v.add(mul(bandpass(v.noise(), (t, x) => 200 + 1800 * Math.min(1, t / .18), 2), env(n, .16, .12)), .7);
      v.add(mul(osc(n, t => 24 + 60 * Math.exp(-t * 6)), env(n, .004, 1.1)), 1.3);
      v.add(mul(osc(n, t => 40 + 30 * Math.exp(-t * 3)), env(n, .01, .7)), .6);
      v.add(mul(lowpass(v.noise(), t => 600 * Math.exp(-t * 2) + 80), env(n, .02, 1.6)), 1.4);
      v.add(highpass(crackle(v, (t, x) => 900 * Math.exp(-t * 1.5), .002), 900), .35);
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
  let peak = out.reduce((a, b) => Math.max(a, Math.abs(b)), 0);
  const loud = kind.startsWith("armageddon");
  if (loud && peak) {
    // Bass everywhere: the sub (under 90 Hz) is driven into harmonics a phone or laptop can play, kept
    // under 250 Hz, and laid back under the sound with the lows lifted besides.
    const sub = lowpass(lowpass(out, 90), 90);
    const drivenSub = sub.map(x => Math.tanh(5 * x / peak) * peak);
    const harmonics = lowpass(lowpass(highpass(drivenSub, 55), 250), 250);
    const lows = lowpass(out, 160);
    const blast = kind === "armageddon_blast";
    out = out.map((x, i) => x + (blast ? .8 : 1.6) * harmonics[i] + (blast ? .2 : .9) * lows[i]);
    peak = out.reduce((a, b) => Math.max(a, Math.abs(b)), 0);
    // Driven into a soft limiter, so the quiet parts stay quiet but everything loud is as loud as it gets
    // (the blast hardest of all: it should hurt).
    const drive = blast ? 6 : kind === "armageddon_charge" ? 4.5 : 3.2;
    out = out.map(x => Math.tanh(drive * x / peak) / Math.tanh(drive));
    // Driving a lopsided wave that hard leaves it off centre: take that back out below hearing.
    out = highpass(out, 12);
    peak = out.reduce((a, b) => Math.max(a, Math.abs(b)), 0);
  }
  // Vorbis overshoots a limited signal a little, so a loud sound still keeps some headroom.
  const gain = peak ? (loud ? .9 : .8) / peak : 0;
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

/** Encodes, then checks the decoded peak: Vorbis overshoots dense, limited sounds, so those are turned down just enough to stay under full scale. */
async function encodeWithin(samples, limit = .96) {
  const ogg = await encode(samples);
  const decoder = new OggVorbisDecoder();
  await decoder.ready;
  const { channelData } = await decoder.decodeFile(new Uint8Array(ogg));
  decoder.free();
  const peak = channelData[0].reduce((a, b) => Math.max(a, Math.abs(b)), 0);
  return peak <= limit ? ogg : encode(samples.map(x => x * (limit - .03) / peak));
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
    else writeFileSync(join(OUT, `${name}.ogg`), await encodeWithin(samples));
  }
  console.log(`Generated ${specs.length} ${wavOnly ? "WAV previews in work/sound-preview" : "Ogg Vorbis assets"}.`);
}

await main();
