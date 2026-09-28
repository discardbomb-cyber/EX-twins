#!/usr/bin/env python3
"""Create deterministic, original combat effects and encode them as mono Ogg Vorbis.

The script deliberately has no network or package-install path.  It synthesizes PCM
with NumPy and the Python standard library, then uses a locally installed ffmpeg or
oggenc solely as the Vorbis encoder.  Use --wav-only to inspect sources when no
encoder exists; game assets are never replaced with WAV files.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROJECT_DEPS = ROOT / "work/audio-deps"
if PROJECT_DEPS.is_dir():
    sys.path.insert(0, str(PROJECT_DEPS))

import numpy as np

OUT = ROOT / "src/main/resources/assets/relics_addon/sounds/combat"
EVIDENCE = ROOT / "reports/evidence/AUDIO-P08"
RATE = 48_000

# name, audible family, seconds.  Names must match sounds.json exactly.
SPECS = [
    ("rf_attack_1", "rf_attack", .24), ("rf_attack_2", "rf_attack", .28),
    ("rf_impact_1", "rf_impact", .18), ("rf_impact_2", "rf_impact", .22),
    ("mana_attack_1", "mana_attack", .42), ("mana_attack_2", "mana_attack", .48),
    ("mana_impact_1", "mana_impact", .32), ("mana_impact_2", "mana_impact", .36),
    ("twins_lightning_attack_1", "twins_lightning", .26), ("twins_lightning_attack_2", "twins_lightning", .30),
    ("twins_bolt_attack_1", "twins_bolt", .38), ("twins_bolt_attack_2", "twins_bolt", .44),
    ("twins_lightning_impact_1", "twins_lightning", .20), ("twins_lightning_impact_2", "twins_lightning", .24),
    ("twins_bolt_impact_1", "twins_bolt", .30), ("twins_bolt_impact_2", "twins_bolt", .34),
    ("rf_summon", "rf_summon", .52), ("rf_dismiss", "rf_dismiss", .34),
    ("mana_summon", "mana_summon", .62), ("mana_dismiss", "mana_dismiss", .46),
    ("twins_summon", "twins_summon", .68), ("twins_dismiss", "twins_dismiss", .50),
    ("shield_rf_absorb_1", "shield_rf_absorb", .18), ("shield_rf_absorb_2", "shield_rf_absorb", .22),
    ("shield_rf_cell_break", "shield_rf_break", .38), ("shield_rf_collapse", "shield_rf_collapse", .68),
    ("shield_mana_absorb_1", "shield_mana_absorb", .25), ("shield_mana_absorb_2", "shield_mana_absorb", .30),
    ("shield_mana_cell_break", "shield_mana_break", .46), ("shield_mana_collapse", "shield_mana_collapse", .76),
    ("shield_twins_absorb_1", "shield_twins_absorb", .22), ("shield_twins_absorb_2", "shield_twins_absorb", .26),
    ("shield_twins_cell_break", "shield_twins_break", .42), ("shield_twins_collapse", "shield_twins_collapse", .78),
]


def seed_for(name: str) -> int:
    return int.from_bytes(hashlib.sha256(name.encode("ascii")).digest()[:8], "little")


def envelope(t: np.ndarray, duration: float, decay: float) -> np.ndarray:
    attack = np.clip(t / .012, 0.0, 1.0)
    release = np.clip((duration - t) / .020, 0.0, 1.0)
    return attack * release * np.exp(-t / decay)


def sweep(t: np.ndarray, low: float, high: float, duration: float) -> np.ndarray:
    phase = 2.0 * math.pi * (low * t + (high - low) * t * t / (2.0 * duration))
    return np.sin(phase)


def synthesize(name: str, family: str, duration: float) -> np.ndarray:
    count = int(RATE * duration)
    t = np.arange(count, dtype=np.float64) / RATE
    rng = np.random.default_rng(seed_for(name))
    noise = rng.normal(0.0, 1.0, count)
    high_noise = noise - np.concatenate(([0.0], noise[:-1]))
    e = envelope(t, duration, max(.08, duration * .48))

    if family.startswith("rf") or family.startswith("shield_rf"):
        core = .58 * high_noise + .32 * np.sign(np.sin(2 * math.pi * (155 + 75 * t) * t))
        if "collapse" in family or "summon" in family:
            core += .48 * sweep(t, 110, 46, duration)
        signal = core * e
    elif family.startswith("mana") or family.startswith("shield_mana"):
        core = .60 * sweep(t, 260, 950, duration) + .24 * sweep(t, 510, 1320, duration)
        shimmer = .13 * high_noise * (np.sin(2 * math.pi * 18 * t) + 1.0)
        if "break" in family or "collapse" in family:
            core += .34 * sweep(t, 780, 180, duration)
        signal = (core + shimmer) * e
    elif family.startswith("twins") or family.startswith("shield_twins"):
        core = .54 * sweep(t, 68, 42, duration) + .30 * sweep(t, 139, 92, duration)
        rune = .20 * np.sin(2 * math.pi * (310 * t + 46 * np.sin(2 * math.pi * 4 * t)))
        if "lightning" in family:
            core += .34 * high_noise
        if "collapse" in family or "break" in family:
            core += .23 * sweep(t, 226, 52, duration)
        signal = (core + rune) * e
    else:
        raise ValueError(f"Unhandled family {family}")

    # Zero DC, cap safely below full scale and force a click-free tail.
    signal -= float(np.mean(signal))
    peak = float(np.max(np.abs(signal)))
    signal = signal * (.78 / peak) if peak else signal
    fade = min(int(RATE * .018), count // 3)
    signal[:fade] *= np.linspace(0.0, 1.0, fade, endpoint=True)
    signal[-fade:] *= np.linspace(1.0, 0.0, fade, endpoint=True)
    return signal.astype(np.float32)


def write_wav(path: Path, samples: np.ndarray) -> None:
    pcm = np.clip(samples * 32767.0, -32768.0, 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(pcm.tobytes())


def soundfile_module():
    try:
        import soundfile  # type: ignore[import-not-found]
        return soundfile
    except ImportError:
        return None


def encoder() -> tuple[str, str | None] | None:
    configured = os.environ.get("COMBAT_SOUNDS_ENCODER")
    if configured:
        return ("ffmpeg", configured)
    for command in ("ffmpeg", "oggenc"):
        found = shutil.which(command)
        if found:
            return command, found
    if soundfile_module() is not None:
        return "soundfile", None
    return None


def encode(kind: str, executable: str | None, source: Path, target: Path) -> None:
    if kind == "ffmpeg":
        command = [executable, "-y", "-loglevel", "error", "-i", str(source), "-ac", "1", "-ar", str(RATE),
                   "-c:a", "libvorbis", "-q:a", "4", str(target)]
    elif kind == "oggenc":
        command = [executable, "--quiet", "--quality", "4", "--output", str(target), str(source)]
    else:
        soundfile = soundfile_module()
        if soundfile is None:
            raise RuntimeError("soundfile fallback was selected but cannot be imported")
        pcm, sample_rate = soundfile.read(str(source), dtype="float32", always_2d=False)
        soundfile.write(str(target), pcm, sample_rate, format="OGG", subtype="VORBIS")
        return
    subprocess.run(command, check=True)


def analyze_ogg(path: Path) -> tuple[int, int, float, float, float]:
    soundfile = soundfile_module()
    if soundfile is None:
        raise RuntimeError("soundfile is required to measure generated Ogg Vorbis output")
    decoded, sample_rate = soundfile.read(str(path), dtype="float64", always_2d=True)
    return decoded.shape[1], sample_rate, len(decoded) / sample_rate, float(np.max(np.abs(decoded))), float(np.mean(decoded))


def remove_codec_dc(path: Path) -> tuple[int, int, float, float, float]:
    """Vorbis can introduce a minute offset; remove it in decoded PCM and re-encode."""
    soundfile = soundfile_module()
    if soundfile is None:
        return analyze_ogg(path)
    for _ in range(3):
        decoded, sample_rate = soundfile.read(str(path), dtype="float64", always_2d=True)
        dc = float(np.mean(decoded))
        peak = float(np.max(np.abs(decoded)))
        if abs(dc) <= .0005 and peak <= .8:
            break
        decoded -= dc
        peak = float(np.max(np.abs(decoded)))
        if peak > .78:
            decoded *= .78 / peak
        soundfile.write(str(path), decoded, sample_rate, format="OGG", subtype="VORBIS")
    return analyze_ogg(path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--wav-only", action="store_true", help="write inspection WAVs, never game assets")
    args = parser.parse_args()
    selected = encoder()
    if not args.wav_only and selected is None:
        print("No local ffmpeg or oggenc found. Install neither automatically; rerun on a machine with one.", file=sys.stderr)
        return 2

    wav_dir = EVIDENCE / "raw-wav" if args.wav_only else None
    if wav_dir:
        wav_dir.mkdir(parents=True, exist_ok=True)
    if not args.wav_only:
        OUT.mkdir(parents=True, exist_ok=True)
        EVIDENCE.mkdir(parents=True, exist_ok=True)

    manifest = []
    source_manifest = []
    with tempfile.TemporaryDirectory(prefix="relics-combat-sounds-") as temporary:
        temp = Path(temporary)
        for name, family, duration in SPECS:
            samples = synthesize(name, family, duration)
            source = (wav_dir / f"{name}.wav") if args.wav_only else (temp / f"{name}.wav")
            write_wav(source, samples)
            source_manifest.append({
                "file": source.name, "family": family, "sample_rate": RATE, "channels": 1,
                "duration_seconds": round(len(samples) / RATE, 4), "peak": round(float(np.max(np.abs(samples))), 5),
                "dc": round(float(np.mean(samples)), 7), "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            })
            target = OUT / f"{name}.ogg"
            if not args.wav_only:
                encode(*selected, source, target)
                channels, sample_rate, actual_duration, actual_peak, actual_dc = remove_codec_dc(target)
                manifest.append({
                    "file": f"combat/{target.name}", "family": family, "sample_rate": sample_rate, "channels": channels,
                    "duration_seconds": round(actual_duration, 4), "peak": round(actual_peak, 5),
                    "dc": round(actual_dc, 7), "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
                })
    if not args.wav_only:
        (EVIDENCE / "audio-manifest.json").write_text(json.dumps({"generator": "tools/build_combat_sounds.py", "assets": manifest}, indent=2) + "\n", encoding="utf-8")
        print(f"Generated {len(manifest)} original Ogg Vorbis assets with {selected[0]}.")
    else:
        (EVIDENCE / "procedural-source-manifest.json").write_text(
            json.dumps({"generator": "tools/build_combat_sounds.py", "assets": source_manifest}, indent=2) + "\n", encoding="utf-8")
        print(f"Generated {len(source_manifest)} original WAV inspection sources; no game assets were written.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
