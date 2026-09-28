"""Assemble the explicit native hive capture into a looping preview GIF."""

import argparse
from pathlib import Path

from PIL import Image, ImageOps


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("screenshots", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    files = [args.screenshots / f"relics-hive-gif-{i:03d}.png" for i in range(120)]
    for path in files:
        if not path.is_file():
            raise SystemExit(f"Missing native frame: {path}")
    sheet = Image.new("RGB", (320, 180 * 12))
    for i, path in enumerate(files[::10]):
        with Image.open(path) as frame:
            sheet.paste(frame.convert("RGB").resize((320, 180), Image.Resampling.LANCZOS), (0, i * 180))
    palette = sheet.quantize(colors=224)
    frames = []
    for path in files:
        with Image.open(path) as source:
            frame = ImageOps.pad(source.convert("RGB"), (1280, 720), method=Image.Resampling.LANCZOS, color=(32, 34, 38))
            frames.append(frame.quantize(palette=palette, dither=Image.Dither.NONE))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    frames[0].save(args.output, save_all=True, append_images=frames[1:], duration=100, loop=0, optimize=False, disposal=1)
    with Image.open(args.output) as gif:
        assert gif.n_frames == 120 and gif.size == (1280, 720)
        duration = 0
        for i in range(gif.n_frames):
            gif.seek(i)
            duration += gif.info["duration"]
        assert duration == 12000
    print(f"{args.output}: 120 native frames, 1280x720, 12 seconds, {args.output.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
