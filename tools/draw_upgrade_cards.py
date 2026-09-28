"""Draw native 22x31 Relics upgrade cards directly on the pixel grid."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "src/main/resources/assets/relics/textures/abilities"
PALETTES = {
    "rf": {"bg": "#07151f", "edge": "#12394b", "shade": "#1b718a", "base": "#36d5eb", "light": "#dcffff", "accent": "#ffc967"},
    "mana": {"bg": "#082020", "edge": "#145351", "shade": "#28968a", "base": "#5ce3c7", "light": "#e1fff0", "accent": "#ffe18b"},
    "twins": {"bg": "#100915", "edge": "#2c1738", "shade": "#57256e", "base": "#a84ed2", "light": "#f0c2ff", "accent": "#75d5ef"},
}


def point(draw, x, y, color):
    if 0 <= x < 22 and 0 <= y < 31:
        draw.point((x, y), fill=color)


def line(draw, points, color):
    draw.line(points, fill=color, width=1)


def hex_plate(draw, cx, cy, palette, radius=4):
    draw.polygon([(cx, cy - radius), (cx + radius, cy - 2), (cx + radius, cy + 2), (cx, cy + radius),
                  (cx - radius, cy + 2), (cx - radius, cy - 2)], fill=palette["edge"])
    draw.polygon([(cx, cy - radius + 1), (cx + radius - 1, cy - 1), (cx + radius - 1, cy + 1),
                  (cx, cy + radius - 1), (cx - radius + 1, cy + 1), (cx - radius + 1, cy - 1)], fill=palette["shade"])
    line(draw, [(cx - radius + 1, cy - 1), (cx, cy - radius + 1), (cx + radius - 1, cy - 1)], palette["base"])
    point(draw, cx, cy - 1, palette["light"])


def drone(draw, cx, cy, palette):
    draw.rectangle((cx - 2, cy - 2, cx + 2, cy + 2), fill=palette["edge"])
    draw.rectangle((cx - 1, cy - 1, cx + 1, cy + 1), fill=palette["shade"])
    point(draw, cx, cy - 1, palette["light"])
    point(draw, cx + 1, cy, palette["base"])


def seal(draw, cx, cy, palette, radius=4):
    draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=palette["edge"])
    draw.ellipse((cx - radius + 1, cy - radius + 1, cx + radius - 1, cy + radius - 1), fill=palette["base"])
    draw.rectangle((cx - 1, cy - 2, cx + 1, cy + 2), fill=palette["shade"])
    point(draw, cx, cy - 2, palette["light"])


def card(family):
    palette = PALETTES[family]
    image = Image.new("RGBA", (22, 31), palette["bg"])
    draw = ImageDraw.Draw(image)
    for x in range(1, 21):
        point(draw, x, 1, palette["edge"])
        point(draw, x, 29, palette["edge"])
    for y in range(2, 29):
        point(draw, 1, y, palette["edge"])
        point(draw, 20, y, palette["edge"])
    for x, y in ((3, 4), (18, 5), (3, 25), (18, 27), (10, 3)):
        point(draw, x, y, palette["shade"])
    return image, draw, palette


def shield_restoration(family):
    image, draw, palette = card(family)
    if family == "rf":
        hex_plate(draw, 10, 16, palette, 7)
        line(draw, [(4, 25), (7, 22), (7, 19)], palette["base"])
        line(draw, [(17, 25), (14, 22), (14, 19)], palette["base"])
        line(draw, [(5, 23), (7, 23)], palette["light"])
        line(draw, [(14, 23), (16, 23)], palette["light"])
        draw.polygon([(11, 5), (7, 12), (10, 12), (8, 18), (14, 9), (11, 9), (13, 5)], fill=palette["accent"])
        point(draw, 10, 10, palette["light"])
    elif family == "mana":
        draw.polygon([(10, 4), (6, 12), (6, 20), (10, 26), (11, 26), (15, 20), (15, 12), (11, 4)], fill=palette["edge"])
        draw.polygon([(10, 7), (8, 13), (8, 19), (10, 23), (11, 23), (13, 19), (13, 13), (11, 7)], fill=palette["shade"])
        line(draw, [(10, 9), (10, 17), (7, 20)], palette["base"])
        line(draw, [(11, 14), (14, 17), (11, 23)], palette["base"])
        point(draw, 10, 12, palette["light"])
        line(draw, [(3, 24), (6, 24), (6, 21)], palette["accent"])
        line(draw, [(18, 24), (15, 24), (15, 21)], palette["accent"])
    else:
        seal(draw, 8, 13, palette, 5)
        seal(draw, 13, 19, palette, 5)
        line(draw, [(4, 25), (7, 22), (10, 22), (13, 25)], palette["base"])
        line(draw, [(6, 5), (8, 7), (10, 7)], palette["accent"])
        point(draw, 10, 15, palette["light"])
    return image


def shield_stabilization():
    image, draw, palette = card("twins")
    seal(draw, 7, 13, palette, 5)
    seal(draw, 14, 13, palette, 5)
    hex_plate(draw, 10, 22, palette, 6)
    line(draw, [(7, 7), (10, 4), (14, 7)], palette["accent"])
    line(draw, [(7, 17), (10, 20), (14, 17)], palette["light"])
    point(draw, 10, 4, palette["light"])
    point(draw, 10, 27, palette["accent"])
    return image


def combat_protocol(family):
    image, draw, palette = card(family)
    drone(draw, 10, 15, palette)
    for x, y in ((4, 8), (17, 8), (3, 22), (18, 22)):
        drone(draw, x, y, palette)
        line(draw, [(10, 15), (x, y)], palette["shade"])
    line(draw, [(10, 4), (10, 8)], palette["accent"])
    line(draw, [(6, 15), (3, 15)], palette["accent"])
    line(draw, [(14, 15), (18, 15)], palette["accent"])
    line(draw, [(10, 22), (10, 27)], palette["accent"])
    point(draw, 10, 15, palette["light"])
    return image


def support_protocol(family):
    image, draw, palette = card(family)
    hex_plate(draw, 10, 16, palette, 6)
    line(draw, [(10, 9), (10, 21)], palette["light"])
    line(draw, [(5, 15), (15, 15)], palette["light"])
    for x, y in ((4, 7), (17, 7), (4, 25), (17, 25)):
        drone(draw, x, y, palette)
        line(draw, [(10, 16), (x, y)], palette["base"])
    point(draw, 10, 15, palette["accent"])
    return image


def recovery_protocol(family):
    image, draw, palette = card(family)
    hex_plate(draw, 10, 16, palette, 6)
    line(draw, [(6, 10), (10, 7), (15, 10), (15, 19), (10, 24), (6, 20), (6, 14)], palette["base"])
    line(draw, [(6, 14), (4, 16), (7, 17)], palette["light"])
    line(draw, [(15, 10), (17, 8), (14, 8)], palette["light"])
    for x, y in ((3, 7), (18, 24), (18, 6)):
        drone(draw, x, y, palette)
    point(draw, 10, 16, palette["accent"])
    return image


def save(image, folder, ability):
    path = DESTINATION / folder / f"{ability}.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    assert image.size == (22, 31)
    assert image.getchannel("A").getextrema() == (255, 255)
    assert len(image.getcolors(1024)) <= 8
    image.save(path)
    print(path.relative_to(ROOT))


for family in PALETTES:
    save(shield_restoration(family), f"{family}_shield", "shield_restoration")
save(shield_stabilization(), "twins_shield", "shield_stabilization")
for family in PALETTES:
    for ability, painter in (("combat_protocol", combat_protocol), ("support_protocol", support_protocol),
                             ("recovery_protocol", recovery_protocol)):
        save(painter(family), f"{family}_hive", ability)
