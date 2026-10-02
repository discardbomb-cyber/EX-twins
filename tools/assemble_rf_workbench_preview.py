"""Assemble native Minecraft/Photon frames; filenames carry game ticks, so timing is preserved."""
import argparse
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

parser = argparse.ArgumentParser()
parser.add_argument('--frames', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
paths = sorted(Path(args.frames).glob('rf-workbench-*.png'))
if len(paths) < 20:
    raise RuntimeError(f'Insufficient capture: {len(paths)} frames')
sequence = json.loads(Path('artifacts/blockbench/rf_workbench/preview_sequence.json').read_text())
labels = {'uncharged': 'Без заряда', 'charged_closed': 'Заряжен', 'player_approach': 'Раскрытие',
          'charged_open': 'Готов к крафту', 'crafting_start': 'Отделение угловых узлов',
          'crafting': 'Крафт · Photon', 'crafting_end': 'Возвращение узлов', 'player_leave': 'Закрытие'}
font = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 21)
small = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 15)
def clip_at(tick):
    at = tick / 20
    for clip, duration in sequence:
        if at < duration:
            return clip
        at -= duration
    return sequence[-1][0]

frames, ticks = [], []
for p in paths:
    tick = int(p.stem.rsplit('-', 1)[1])
    # Frame the station tightly; this crops captured pixels without altering the effects.
    image = Image.open(p).convert('RGB').crop((200, 175, 860, 768)).resize((720, 647), Image.Resampling.LANCZOS)
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 720, 56), fill=(14, 21, 28))
    draw.text((18, 8), labels[clip_at(tick)], font=font, fill=(98, 231, 255))
    draw.text((18, 34), 'EX-twins · Minecraft 1.21.1 · NeoForge 21.1.251 · Photon', font=small, fill=(187, 204, 213))
    frames.append(image)
    ticks.append(tick)
sample = Image.new('RGB', (160 * min(len(frames), 12), 120))
for i in range(min(len(frames), 12)):
    sample.paste(frames[round(i * (len(frames) - 1) / 11)].resize((160, 120)), (i * 160, 0))
palette = sample.quantize(colors=256)
indexed = [frame.quantize(palette=palette, dither=Image.Dither.NONE) for frame in frames]
durations = [max(20, (ticks[i + 1] - tick) * 50) if i + 1 < len(ticks) else max(50, (280 - tick) * 50)
             for i, tick in enumerate(ticks)]
output = Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)
indexed[0].save(output, save_all=True, append_images=indexed[1:], duration=durations, loop=0, disposal=2, optimize=True)
frames[0].save(output.with_suffix('.webp'), save_all=True, append_images=frames[1:], duration=durations, loop=0, quality=86, method=4)
with Image.open(output) as result:
    if result.n_frames < 20:
        raise RuntimeError('Animation collapsed into static image')
    total_ms = 0
    for i in range(result.n_frames):
        result.seek(i)
        total_ms += result.info['duration']
    print(f'PASS native GIF: {result.n_frames} frames, {total_ms / 1000:.2f}s, {output.stat().st_size / 1024 / 1024:.2f} MiB; {output}')
for tick, name in [(15, 'uncharged'), (82, 'open'), (151, 'crafting')]:
    frame = frames[min(range(len(ticks)), key=lambda i: abs(ticks[i] - tick))]
    frame.save(output.with_name(f'rf-workbench-native-{name}.png'))
