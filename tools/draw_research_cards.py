"""Direct 22x31 pixel authorship, explicitly approved by the user after draft rejection."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / "art/research"
ART.mkdir(parents=True, exist_ok=True)
DEST = ROOT / "src/main/resources/assets/relics/textures/abilities"
PALETTES = {
    "rf": dict(edge="#183542", shade="#24596d", base="#2885a4", light="#44d9ed", white="#d4faff", accent="#ffc967"),
    "mana": dict(edge="#24424d", shade="#237b80", base="#3db7ab", light="#88e8cc", white="#f4f3ce", accent="#cba15a"),
    "twins": dict(edge="#14101d", shade="#302040", base="#683b8c", light="#ad69dc", white="#e3b0f7", accent="#874bbc"),
}
GLOW = {
    "rf": ["#070e23", "#0a1b34", "#0d2b48", "#134662", "#256f88"],
    "mana": ["#061b22", "#092c32", "#104239", "#1b6251", "#368e6b"],
    "twins": ["#080610", "#130c21", "#251236", "#3d1d53", "#642f83"],
}


def plate(d, cx, cy, p, r=4):
    d.polygon([(cx,cy-r),(cx+r,cy-2),(cx+r,cy+2),(cx,cy+r),(cx-r,cy+2),(cx-r,cy-2)], fill=p["edge"])
    d.polygon([(cx,cy-r+1),(cx+r-1,cy-1),(cx+r-1,cy+1),(cx,cy+r-1),(cx-r+1,cy+1),(cx-r+1,cy-1)], fill=p["base"])
    d.line([(cx-r+1,cy-1),(cx,cy-r+1),(cx+r-1,cy-1)], fill=p["light"])
    d.line([(cx,cy+r-1),(cx+r-1,cy+1)], fill=p["shade"])


def droplet(d, cx, cy, p, r=4):
    d.polygon([(cx,cy-r-2),(cx+r,cy),(cx+r-1,cy+3),(cx,cy+4),(cx-r+1,cy+3),(cx-r,cy)], fill=p["edge"])
    d.polygon([(cx,cy-r),(cx+r-1,cy),(cx+r-2,cy+2),(cx,cy+3),(cx-r+2,cy+2),(cx-r+1,cy)], fill=p["base"])
    d.line([(cx-1,cy-2),(cx-2,cy),(cx-1,cy+1)], fill=p["white"])
    d.line([(cx,cy+2),(cx+2,cy)], fill=p["light"])


def seal(d, cx, cy, p, r=5):
    d.ellipse((cx-r,cy-r,cx+r,cy+r), fill=p["edge"])
    d.ellipse((cx-r+1,cy-r+1,cx+r-1,cy+r-1), fill=p["light"])
    d.ellipse((cx-r+2,cy-r+2,cx+r-2,cy+r-2), fill=p["shade"])
    d.line([(cx-2,cy-r+1),(cx,cy-r+1),(cx+2,cy-r+1)], fill=p["white"])
    d.point((cx,cy), fill=p["accent"])


def relics_card(art, family):
    """Paint light on the native grid; never blur, rescale or copy upstream pixels."""
    from PIL import ImageColor
    palette = PALETTES[family]
    lights = {ImageColor.getrgb(palette[key]) for key in ("light", "white", "accent")}
    points = [(x,y) for y in range(31) for x in range(22) if art.getpixel((x,y))[:3] in lights]
    background = Image.new("RGBA", (22,31))
    for y in range(31):
        for x in range(22):
            distance = min((x-px)**2 + (y-py)**2 for px,py in points)
            level = 4 if distance <= 1 else 3 if distance <= 4 else 2 if distance <= 10 else 1 if distance <= 22 else 0
            if x in (0,21) or y in (0,30):
                level = min(level,1)
            background.putpixel((x,y), ImageColor.getrgb(GLOW[family][level]) + (255,))
    background.alpha_composite(art)
    d = ImageDraw.Draw(background)
    for x,y in [(2,4),(19,3),(1,26),(19,28),(3,12)]:
        if art.getpixel((x,y))[3] == 0:
            d.point((x,y), fill=palette["shade"])
    return background


def draw_card(family, ability):
    card = Image.new("RGBA", (22,31))
    d, p = ImageDraw.Draw(card), PALETTES[family]
    if ability == family + "_shield":
        if family == "rf":
            d.polygon([(10,2),(11,2),(19,7),(18,19),(15,24),(11,28),(10,28),(6,24),(3,19),(2,7)], fill=p["edge"])
            d.polygon([(10,4),(11,4),(17,8),(16,18),(13,23),(11,25),(10,25),(8,23),(5,18),(4,8)], fill=p["light"])
            d.polygon([(10,6),(11,6),(15,9),(14,18),(11,23),(10,23),(7,18),(6,9)], fill=p["shade"])
            d.line([(4,8),(10,4),(11,4)], fill=p["white"])
            d.polygon([(11,8),(8,15),(11,15),(9,22),(14,13),(11,13),(13,8)], fill=p["white"])
            d.point((17,5), fill=p["accent"])
        elif family == "mana":
            d.polygon([(4,10),(2,16),(3,22),(6,26),(10,28),(11,28),(15,26),(18,22),(19,16),(17,10),
                       (17,18),(15,23),(11,25),(10,25),(6,23),(4,18)], fill=p["edge"])
            d.line([(3,15),(4,21),(7,25),(10,27),(11,27),(14,25),(17,21),(18,15)], fill=p["accent"])
            d.line([(4,16),(5,21),(8,24),(10,25),(11,25),(13,24),(16,21)], fill=p["white"])
            droplet(d,10,14,p,5)
            d.line((10,2,10,5), fill=p["light"])
            d.point((9,3), fill=p["white"])
        else:
            d.line([(5,7),(3,10),(2,16),(4,22),(8,26),(14,27),(18,23),(19,16),(17,8)], fill=p["base"])
            seal(d,8,12,p,6)
            seal(d,13,20,p,6)
            d.line([(7,12),(8,11),(9,12),(8,13),(7,12)], fill=p["white"])
            d.line([(12,20),(13,19),(14,20),(13,21),(12,20)], fill=p["white"])
            d.line((10,2,10,5), fill=p["accent"])
            d.line((9,3,11,3), fill=p["white"])
    elif ability == "damage_distribution":
        if family == "rf":
            d.line([(10,8),(8,12),(5,13),(6,15),(4,18)], fill=p["light"])
            d.line([(11,11),(15,13),(14,15),(17,18)], fill=p["light"])
            d.line([(10,12),(11,16),(9,20),(10,25)], fill=p["light"])
            plate(d,4,19,p,3)
            plate(d,17,19,p,3)
            plate(d,10,26,p,3)
            d.polygon([(11,2),(7,7),(10,7),(8,11),(14,5),(11,5),(13,2)], fill=p["accent"])
            d.point((10,5), fill=p["white"])
        elif family == "mana":
            d.line([(10,12),(7,13),(4,16),(4,19)], fill=p["light"])
            d.line([(11,12),(14,13),(17,16),(17,19)], fill=p["light"])
            d.line((10,13,10,26), fill=p["accent"])
            droplet(d,10,8,p,4)
            droplet(d,4,21,p,3)
            droplet(d,17,21,p,3)
            droplet(d,10,26,p,2)
        else:
            d.line([(10,6),(4,23),(17,23),(10,6)], fill=p["accent"])
            d.line([(10,6),(10,17),(4,23)], fill=p["base"])
            d.line((10,17,17,23), fill=p["base"])
            seal(d,10,7,p,4)
            seal(d,5,23,p,4)
            seal(d,16,23,p,4)
            d.line((10,14,10,19), fill=p["light"])
            d.line((8,16,12,16), fill=p["white"])
    elif family == "rf":
        plate(d,4,7,p,3)
        plate(d,17,7,p,3)
        d.line([(4,12),(7,15),(7,17)], fill=p["white"])
        d.line([(17,12),(14,15),(14,17)], fill=p["white"])
        d.line((5,17,7,17), fill=p["white"])
        d.line((14,17,16,17), fill=p["white"])
        plate(d,10,23,p,5)
        d.line((10,21,10,24), fill=p["accent"])
        d.line((9,22,11,22), fill=p["white"])
    else:
        d.line([(6,6),(3,9),(2,14),(4,18),(6,19)], fill=p["accent"])
        d.line([(15,6),(18,9),(19,14),(17,18),(15,19)], fill=p["accent"])
        d.line([(5,10),(4,13),(5,16),(7,17)], fill=p["light"])
        d.line([(16,10),(17,13),(16,16),(14,17)], fill=p["light"])
        d.line((7,15,7,17), fill=p["white"])
        d.line((14,15,14,17), fill=p["white"])
        droplet(d,10,22,p,5)
        d.line((10,3,10,7), fill=p["light"])
        d.line((8,5,12,5), fill=p["white"])
    assert card.size == (22,31) and card.getbbox()
    assert set(card.getchannel("A").get_flattened_data()) == {0,255}
    assert len(card.getcolors(1024)) <= 7
    return relics_card(card, family)


cards = {}
report = {"mode": "direct native-grid pixel art, explicitly user approved", "style": "original Relics dark illustrated ability cards; no upstream art shipped", "cards": {}}
for family in ("rf", "mana", "twins"):
    item = family + "_shield"
    folder = DEST / item
    folder.mkdir(parents=True, exist_ok=True)
    abilities = [item, "damage_distribution"] + ([] if family == "twins" else ["shield_gather"])
    for ability in abilities:
        card = draw_card(family, ability)
        card.save(folder / (ability + ".png"))
        key = family + "-" + ("barrier" if ability == item else "share" if ability == "damage_distribution" else "gather")
        card.save(ART / (key + "-22x31.png"))
        cards[key] = card
        report["cards"][item + "/" + ability] = {"size": list(card.size), "colors": len(card.getcolors(1024)), "pixel_art": key}

preview = Image.new("RGB", (780,790), "#22262d")
d = ImageDraw.Draw(preview)
for column, family in enumerate(("rf","mana","twins")):
    for row, ability in enumerate(("barrier","share","gather")):
        name = family + "-" + ability
        if name not in cards:
            continue
        card = cards[name]
        x, y = column * 260, row * 252
        d.text((x+16,y+12), name, fill="#eef5fa")
        preview.paste(card,(x+216,y+12),card)
        enlarged = card.resize((132,186), Image.Resampling.NEAREST)
        preview.paste(enlarged,(x+64,y+48),enlarged)
d.text((16,766), "Native 22x31 / enlarged 6x. Dark illustrated cards matching the Relics UI.", fill="#b2bec9")
preview.save(ART / "cards-preview-v3.png")
(ART / "manifest.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report))
