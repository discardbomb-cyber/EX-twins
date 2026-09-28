"""Build a captioned showcase from native renderer frames and verified UI captures."""

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("screenshots", type=Path)
    parser.add_argument("ui", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--font", type=Path, required=True)
    args = parser.parse_args()
    font = ImageFont.truetype(str(args.font), 22)
    title_font = ImageFont.truetype(str(args.font), 28)
    small = ImageFont.truetype(str(args.font), 16)
    scenes = []

    def shield(index):
        if 100 <= index < 125:
            return "ТРИ ТИПА ЗАЩИТЫ", "RF: панели. Mana: цельное стекло. Twins: чёрно-фиолетовые стекло и сегменты."
        if index < 25:
            return "ПЕРЕХВАТ СНАРЯДА", "Защита возникает со стороны угрозы; радиус можно менять в настройках щита."
        if index < 45:
            return "ПОГЛОЩЕНИЕ", "Подсветка места попадания. Сначала расходуется общий буфер, затем HP ячеек."
        if index < 70:
            return "РАЗРУШЕНИЕ", "Вспышка сломанной ячейки. После исчерпания буфера пробоины пропускают урон."
        if index < 100:
            return "НЕТ УГРОЗЫ", "Реактивные щиты затихают после завершения эффекта попадания."
        if index < 175:
            return "ЛОКАЛЬНОЕ ПОВРЕЖДЕНИЕ", "420 ячеек с отдельным HP и общий буфер до 5000 HP на максимальной прокачке."
        if index < 225:
            return "СБОР ЩИТА", "RF и Mana стягивают живые ячейки к пробоине. Это перенос защиты, не лечение."
        return "НЕПРЕРЫВНЫЕ ВОЛНЫ", "Новые попадания добавляют свои волны по поверхности, не обрывая предыдущие."

    # Lead with a complete field, then show its actual response timeline.
    for index in list(range(100, 125)) + list(range(100)) + list(range(125, 290)):
        heading, caption = shield(index)
        scenes.append((args.screenshots / f"relics-shield-gif-{index:03d}.png", 50, heading, caption))

    for index in range(180):
        if index < 10:
            heading, caption = "УЛЬИ RF / MANA / TWINS", "Анимированные 3D-модели амулетов. От 12 до 250 дронов каждого типа."
        elif index < 40:
            heading, caption = "ВЫЛЕТ К ЦЕЛИ", "Рой собирается в пульсирующую каплю со слабыми волнами."
        elif index < 85:
            heading, caption = "БОЕВЫЕ ФОРМАЦИИ", "RF: молнии. Mana: синие снаряды. Twins: фиолетовые молнии и снаряды. Цель удерживается."
        elif index < 120:
            heading, caption = "ВОЗВРАЩЕНИЕ В ПОЯС", "После завершения задачи дроны возвращаются в пояс и исчезают. Никаких крыльев в покое."
        else:
            heading, caption = "ДРОНЫ-ЛЕКАРИ", "Отдельная группа поддержки владельца: не атакует и не перехватывает снаряды."
        scenes.append((args.screenshots / f"relics-hive-gif-{index:03d}.png", 100, heading, caption))

    scenes.append((args.ui / "relics-ui-hive-settings-1280x720.png", 4000,
                   "МЕНЮ ЗАДАЧ / H", "Своё распределение у каждого улья: число лекарей, остальные защищают и атакуют."))
    for page in range(1, 5):
        scenes.append((args.ui / f"relics-research-resources-page-{page:02d}.png", 2500,
                       "ПРОКАЧКА И ИССЛЕДОВАНИЯ", "6 реликвий, по 3 дополнительных улучшения. Свои карточки и созвездия, уровень и ранг Relics."))

    for path, *_ in scenes:
        if not path.is_file():
            raise SystemExit(f"Missing native capture: {path}")
    total_ms = sum(scene[1] for scene in scenes)

    def compose(scene, elapsed):
        path, duration, heading, caption = scene
        canvas = Image.new("RGB", (1280, 832), (18, 20, 24))
        with Image.open(path) as source:
            source = ImageOps.pad(source.convert("RGB"), (1280, 704), color=(32, 34, 38), method=Image.Resampling.LANCZOS)
            canvas.paste(source, (0, 52))
        draw = ImageDraw.Draw(canvas)
        draw.text((22, 10), "EX-twins", fill="#f4f5f8", font=title_font)
        draw.text((205, 14), heading, fill="#c6bbff", font=font)
        draw.text((22, 761), caption, fill="#e6ebf2", font=font)
        draw.text((22, 793), "Демонстрация игровых моделей, эффектов и интерфейса. Постановочные сцены, не бой в мире. GIF без звука.",
                  fill="#9da7b4", font=small)
        for text, used_font in [(caption, font), (heading, font)]:
            assert used_font.getlength(text) < (1236 if text == caption else 1030), text
        draw.rectangle((0, 828, int(1280 * (elapsed + duration) / total_ms), 831), fill="#ae77ff")
        return canvas

    samples = list(range(0, len(scenes), 12)) + list(range(len(scenes) - 5, len(scenes)))
    palette_sheet = Image.new("RGB", (320, 208 * len(samples)))
    for row, index in enumerate(samples):
        palette_sheet.paste(compose(scenes[index], 0).resize((320, 208), Image.Resampling.LANCZOS), (0, row * 208))
    palette = palette_sheet.quantize(colors=240)
    frames, durations = [], []
    elapsed = 0
    for scene in scenes:
        frames.append(compose(scene, elapsed).quantize(palette=palette, dither=Image.Dither.NONE))
        durations.append(scene[1])
        elapsed += scene[1]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    frames[0].save(args.output, save_all=True, append_images=frames[1:], duration=durations,
                   loop=0, optimize=False, disposal=1)
    with Image.open(args.output) as result:
        assert result.size == (1280, 832)
        actual_ms = 0
        for index in range(result.n_frames):
            result.seek(index)
            actual_ms += result.info["duration"]
        assert actual_ms == total_ms, (actual_ms, total_ms)
    proof = Image.new("RGB", (1280, 624))
    indices = [0, 54, 75, 155, 213, 275, 320, 345, 385, 435, 470, 474]
    for position, index in enumerate(indices):
        proof.paste(compose(scenes[index], 0).resize((320, 208), Image.Resampling.LANCZOS),
                    (position % 4 * 320, position // 4 * 208))
    proof.save(args.output.with_suffix(".contact.png"))
    print(f"{args.output}: {len(frames)} frames, {total_ms / 1000:.1f}s, {args.output.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
