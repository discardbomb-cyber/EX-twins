#!/usr/bin/env python3
"""Validate generated Relics combat sounds without downloading codecs or assets."""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROJECT_DEPS = ROOT / "work/audio-deps"
if PROJECT_DEPS.is_dir():
    sys.path.insert(0, str(PROJECT_DEPS))
SOUNDS = ROOT / "src/main/resources/assets/relics_addon/sounds/combat"
SOUNDS_JSON = ROOT / "src/main/resources/assets/relics_addon/sounds.json"
MANIFEST = ROOT / "reports/evidence/AUDIO-P08/audio-manifest.json"
REPORT = ROOT / "reports/evidence/AUDIO-P08/sound-validation.json"


def fail(message: str) -> None:
    raise ValueError(message)


def main() -> int:
    try:
        import soundfile  # type: ignore[import-not-found]
    except ImportError as error:
        fail(f"soundfile is required for decoded validation: {error}")
    data = json.loads(SOUNDS_JSON.read_text(encoding="utf-8"))
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))["assets"]
    listed = {entry["file"] for entry in manifest}
    expected = {node["sounds"][0]["name"].split(":", 1)[1] + ".ogg" for node in data.values()}
    if listed != expected:
        fail(f"sounds.json/resources mismatch: expected {len(expected)}, manifest {len(listed)}")
    if len(listed) != 34:
        fail(f"Expected 34 assets, got {len(listed)}")
    digests: set[str] = set()
    total_seconds = 0.0
    for entry in manifest:
        path = SOUNDS / Path(entry["file"]).name
        raw = path.read_bytes()
        if len(raw) < 256 or not raw.startswith(b"OggS") or b"\x01vorbis" not in raw[:256]:
            fail(f"{path.name} is not a non-empty Ogg Vorbis stream")
        digest = hashlib.sha256(raw).hexdigest()
        if digest != entry["sha256"]:
            fail(f"{path.name} hash does not match generated manifest")
        decoded, sample_rate = soundfile.read(str(path), dtype="float64", always_2d=True)
        duration = len(decoded) / sample_rate
        peak = float(abs(decoded).max()) if len(decoded) else 0.0
        dc = float(decoded.mean()) if len(decoded) else 1.0
        if decoded.shape[1] != 1 or sample_rate not in (44100, 48000):
            fail(f"{path.name} is not permitted mono 44.1/48 kHz audio")
        if not .12 <= duration <= .8 or peak > .81 or abs(dc) > .001:
            fail(f"{path.name} exceeds duration, peak, or DC budget")
        digests.add(digest)
        total_seconds += entry["duration_seconds"]
    if len(digests) != len(manifest) or total_seconds > 18.0:
        fail("sound variants are not distinct or the total source budget is too large")
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps({"ok": True, "assets": len(manifest), "total_seconds": round(total_seconds, 3), "mono": True, "format": "Ogg Vorbis"}, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: {len(manifest)} original mono Ogg Vorbis assets, {total_seconds:.3f}s total")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
