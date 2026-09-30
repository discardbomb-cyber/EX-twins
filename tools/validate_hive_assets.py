"""Audit defender-hive resources and, when supplied, the final production JAR."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import zipfile
from io import BytesIO
from pathlib import Path
from typing import Protocol

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src" / "main" / "resources"
GRADLE_PROPERTIES = ROOT / "gradle.properties"


def gradle_property(name: str) -> str:
    try:
        for line in GRADLE_PROPERTIES.read_text(encoding="utf-8").splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() == name:
                return value.strip()
    except OSError as error:
        fail(f"unable to read {GRADLE_PROPERTIES}: {error}")
    fail(f"missing {name} in {GRADLE_PROPERTIES}")


MOD_VERSION = gradle_property("mod_version")
DEFAULT_JAR = ROOT / "build" / "libs" / f"{gradle_property('mod_archive_name')}-{MOD_VERSION}.jar"
REPORT = ROOT / "build" / "reports" / "hive-assets.json"
HIVES = ("rf_hive", "mana_hive", "twins_hive")
HIVE_SHELLS = {"rf_hive": 4, "mana_hive": 6, "twins_hive": 12}
SHIELDS = ("rf_shield", "mana_shield", "twins_shield")
ALL_RELICS = SHIELDS + HIVES
HIVE_FACE_LIMIT = 8000
SWARM_FACE_LIMIT = 600
DENSE_SWARM_FACE_LIMIT = 100
ALLOWED_ROTATION_ANGLES = {-45, -22.5, 0, 22.5, 45}
REGISTRY_SOURCE = ROOT / "src/main/java/dev/hurtify/relicsaddon/registry/ModItems.java"
REGISTRY_GAME_TESTS = (
    ROOT / "src/main/java/dev/hurtify/relicsaddon/gametest/AcquisitionGameTests.java",
    ROOT / "src/main/java/dev/hurtify/relicsaddon/gametest/ShieldCellGameTests.java",
)
HIVE_TYPE_SOURCE = ROOT / "src/main/java/dev/hurtify/relicsaddon/domain/hive/HiveType.java"
HIVE_STATE_SOURCE = ROOT / "src/main/java/dev/hurtify/relicsaddon/drone/HiveCombatState.java"
HIVE_RENDERER_SOURCE = ROOT / "src/main/java/dev/hurtify/relicsaddon/client/HiveVisualRenderer.java"


class Reader(Protocol):
    def exists(self, name: str) -> bool: ...
    def read_bytes(self, name: str) -> bytes: ...


class DirectoryReader:
    def __init__(self, root: Path):
        self.root = root

    def exists(self, name: str) -> bool:
        return (self.root / name).is_file()

    def read_bytes(self, name: str) -> bytes:
        return (self.root / name).read_bytes()


class ArchiveReader:
    def __init__(self, archive: zipfile.ZipFile):
        self.archive = archive
        self.names = set(archive.namelist())

    def exists(self, name: str) -> bool:
        return name in self.names

    def read_bytes(self, name: str) -> bytes:
        return self.archive.read(name)


def fail(message: str) -> None:
    raise ValueError(message)


def read_json(reader: Reader, name: str) -> dict:
    if not reader.exists(name):
        fail(f"missing {name}")
    try:
        value = json.loads(reader.read_bytes(name))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"invalid JSON {name}: {error}")
    if not isinstance(value, dict):
        fail(f"{name}: root must be an object")
    return value


def walk_json(value: object):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk_json(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_json(child)


def png(reader: Reader, name: str) -> Image.Image:
    if not reader.exists(name):
        fail(f"missing {name}")
    with Image.open(BytesIO(reader.read_bytes(name))) as image:
        return image.convert("RGBA")


def validate_rotation(model: dict, name: str) -> int:
    rotations = 0
    for value in walk_json(model):
        rotation = value.get("rotation")
        if not isinstance(rotation, dict):
            continue
        rotations += 1
        if set(rotation) - {"origin", "axis", "angle", "rescale"}:
            fail(f"{name}: unsupported element rotation fields")
        if rotation.get("axis") not in {"x", "y", "z"}:
            fail(f"{name}: rotation axis must be x, y, or z")
        if rotation.get("angle") not in ALLOWED_ROTATION_ANGLES:
            fail(f"{name}: rotation angle {rotation.get('angle')!r} is not allowed")
        if not isinstance(rotation.get("origin"), list) or len(rotation["origin"]) != 3:
            fail(f"{name}: rotation origin must contain three coordinates")
        if "rescale" in rotation and not isinstance(rotation["rescale"], bool):
            fail(f"{name}: rotation rescale must be boolean")
    return rotations


def obj_details(reader: Reader, name: str) -> tuple[list[str], int, dict[str, int]]:
    if not reader.exists(name):
        fail(f"missing OBJ {name}")
    try:
        lines = reader.read_bytes(name).decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        fail(f"{name}: OBJ is not UTF-8: {error}")
    libraries = [line.split(maxsplit=1)[1] for line in lines if line.startswith("mtllib ") and len(line.split(maxsplit=1)) == 2]
    if not libraries:
        fail(f"{name}: missing material library")
    for library in libraries:
        material_name = name.rsplit("/", 1)[0] + "/" + library
        if not reader.exists(material_name):
            fail(f"{name}: missing material library {material_name}")
    groups: dict[str, int] = {}
    active_group = None
    faces = 0
    for line in lines:
        if line.startswith("g "):
            active_group = line[2:].strip()
            if not active_group or " " in active_group:
                fail(f"{name}: OBJ groups must have one non-empty name")
            groups.setdefault(active_group, 0)
        elif line.startswith("f "):
            if active_group is None:
                fail(f"{name}: face appears before its group")
            faces += 1
            groups[active_group] = groups.get(active_group, 0) + 1
    return libraries, faces, groups


def validate_hive_part(model: dict, name: str, hive: str, group: str, groups: set[str]) -> int:
    expected_type = "minecraft:translucent" if group == "fx" else "minecraft:solid"
    expected_model = f"relics_addon:models/item/{hive}.obj"
    if model.get("loader") != "neoforge:obj" or model.get("model") != expected_model:
        fail(f"{name}: must reference {expected_model} through the OBJ loader")
    if model.get("render_type") != expected_type:
        fail(f"{name}: expected {expected_type}")
    visibility = model.get("visibility")
    if not isinstance(visibility, dict) or set(visibility) != groups:
        fail(f"{name}: visibility must name exactly the hive OBJ groups")
    if any(not isinstance(value, bool) for value in visibility.values()) or [key for key, value in visibility.items() if value] != [group]:
        fail(f"{name}: exactly {group} must be visible")
    return validate_rotation(model, name)


def validate_hive_bounds(reader: Reader, name: str) -> dict:
    vertices = [tuple(map(float, line.split()[1:])) for line in reader.read_bytes(name).decode("utf-8").splitlines()
                if line.startswith("v ")]
    if not vertices or any(len(point) != 3 or not all(math.isfinite(value) for value in point) for point in vertices):
        fail(f"{name}: invalid or missing positions")
    radius = max(math.sqrt(sum((value - .5) ** 2 for value in point)) for point in vertices)
    # Rotation preserves this bound; the widest shell animation adds 0.03 block units.
    if radius + .03 > .50001:
        fail(f"{name}: animated volume leaves the one-block inventory model envelope")
    return {"radius_block_units": round(radius, 6), "maximum_animated_radius": round(radius + .03, 6)}


def validate_hive_models(reader: Reader, report: dict) -> None:
    model_parts = 0
    rotations = 0
    hive_faces = {}
    swarm_faces = {}
    for hive in HIVES:
        root_name = f"assets/relics_addon/models/item/{hive}.json"
        root_model = read_json(reader, root_name)
        if root_model.get("parent") != "minecraft:builtin/entity":
            fail(f"{root_name}: hive root model must use minecraft:builtin/entity")
        groups = ("body", "core", "fx") + tuple(f"shell_{index}" for index in range(HIVE_SHELLS[hive]))
        obj_name = f"assets/relics_addon/models/item/{hive}.obj"
        _, faces, obj_groups = obj_details(reader, obj_name)
        if not 1 <= faces <= HIVE_FACE_LIMIT:
            fail(f"{obj_name}: hive exceeds the {HIVE_FACE_LIMIT} face budget ({faces})")
        if set(obj_groups) != set(groups) or any(obj_groups[group] == 0 for group in groups):
            fail(f"{obj_name}: must contain populated groups {groups}")
        hive_faces[hive] = {"total": faces, "groups": obj_groups, "bounds": validate_hive_bounds(reader, obj_name)}
        for group in groups:
            name = f"assets/relics_addon/models/item/animated/{hive}_{group}.json"
            rotations += validate_hive_part(read_json(reader, name), name, hive, group, set(groups))
            model_parts += 1
        swarm_name = f"assets/relics_addon/models/item/animated/{hive.removesuffix('_hive')}_drone_swarm.json"
        swarm = read_json(reader, swarm_name)
        swarm_model = swarm.get("model")
        if swarm.get("loader") != "neoforge:obj" or not isinstance(swarm_model, str) or not swarm_model.startswith("relics_addon:models/item/") or not swarm_model.endswith(".obj"):
            fail(f"{swarm_name}: must reference a drone OBJ through the OBJ loader")
        model_name = "assets/relics_addon/" + swarm_model.split(":", 1)[1]
        if hive in model_name:
            fail(f"{swarm_name}: must not use the hive inventory OBJ as its drone swarm LOD")
        _, faces, _ = obj_details(reader, model_name)
        if not 1 <= faces <= SWARM_FACE_LIMIT:
            fail(f"{model_name}: ordinary swarm LOD exceeds the {SWARM_FACE_LIMIT} face budget ({faces})")
        base = hive.removesuffix("_hive")
        dense_name = f"assets/relics_addon/models/item/animated/{base}_drone_dense.json"
        dense = read_json(reader, dense_name)
        dense_model = dense.get("model")
        expected_dense_model = f"relics_addon:models/item/{base}_drone_dense.obj"
        if dense.get("loader") != "neoforge:obj" or dense_model != expected_dense_model:
            fail(f"{dense_name}: must reference {expected_dense_model} through the OBJ loader")
        dense_model_name = "assets/relics_addon/" + dense_model.split(":", 1)[1]
        _, dense_faces, _ = obj_details(reader, dense_model_name)
        if not 1 <= dense_faces <= DENSE_SWARM_FACE_LIMIT:
            fail(f"{dense_model_name}: dense swarm exceeds the {DENSE_SWARM_FACE_LIMIT} face budget ({dense_faces})")
        swarm_faces[hive] = {
            "model": swarm_model, "faces": faces,
            "dense_model": dense_model, "dense_faces": dense_faces,
        }
    expected_parts = sum(3 + HIVE_SHELLS[hive] for hive in HIVES)
    if model_parts != expected_parts:
        fail(f"expected {expected_parts} native hive model parts, found {model_parts}")
    report["hive_models"] = {
        "roots": len(HIVES), "parts": model_parts, "shells": HIVE_SHELLS,
        "face_limit": HIVE_FACE_LIMIT, "faces": hive_faces,
        "swarm_models": len(HIVES), "swarm_face_limit": SWARM_FACE_LIMIT,
        "dense_swarm_models": len(HIVES), "dense_swarm_face_limit": DENSE_SWARM_FACE_LIMIT,
        "swarm_faces": swarm_faces, "element_rotations": rotations,
    }


def validate_cards(reader: Reader, report: dict) -> None:
    digests: set[str] = set()
    cards = {}
    for hive in HIVES:
        name = f"assets/relics/textures/abilities/{hive}/{hive}.png"
        content = reader.read_bytes(name) if reader.exists(name) else fail(f"missing {name}")
        digest = hashlib.sha256(content).hexdigest()
        if digest in digests:
            fail(f"{name}: ability card duplicates another hive card")
        digests.add(digest)
        image = png(reader, name)
        if image.size != (22, 31) or image.getchannel("A").getextrema() != (255, 255):
            fail(f"{name}: ability card must be opaque 22x31")
        cards[hive] = {"size": list(image.size), "sha256": digest}
    report["ability_cards"] = cards


def validate_localization(reader: Reader, report: dict) -> None:
    suffixes = ("", ".description", ".stat.drone_count", ".stat.cooldown", ".stat.drone_health", ".stat.attack_damage",
                ".stat.attack_interval_max", ".research", ".experience_source.{hive}_activity")
    common = ("tooltip.relics_addon.hive", "tooltip.relics_addon.hive.health", "message.relics_addon.hive_enabled", "message.relics_addon.hive_disabled")
    for locale in ("en_us", "ru_ru"):
        language = read_json(reader, f"assets/relics_addon/lang/{locale}.json")
        required = set(common)
        for hive in HIVES:
            required.add(f"item.relics_addon.{hive}")
            required.add(f"relics.description.{hive}.description")
            required.update(f"relics.description.{hive}.ability.{hive}{suffix.format(hive=hive)}" for suffix in suffixes)
        missing = sorted(key for key in required if not isinstance(language.get(key), str) or not language[key])
        if missing:
            fail(f"{locale}: missing hive language keys {missing}")
        if language["tooltip.relics_addon.hive"].count("%s") != 3:
            fail(f"{locale}: hive tooltip must contain three placeholders")
        if language["tooltip.relics_addon.hive.health"].count("%s") != 1:
            fail(f"{locale}: hive health tooltip must contain one placeholder")
    report["localization"] = {"locales": 2, "hive_keys_per_locale": 37}


def validate_gameplay(reader: Reader, report: dict) -> None:
    recipes = {name.rsplit("/", 1)[-1] for name in reader_names(reader, "data/relics_addon/recipe/") if name.endswith(".json")}
    advancements = {name.rsplit("/", 1)[-1] for name in reader_names(reader, "data/relics_addon/advancement/recipes/") if name.endswith(".json")}
    expected = {f"{relic}.json" for relic in ALL_RELICS}
    if recipes != expected or advancements != expected:
        fail("expected exactly six recipes and six recipe advancements")
    if any("drone" in name for name in recipes | advancements):
        fail("standalone legacy drone recipes or advancements are present")
    charm = read_json(reader, "data/curios/tags/item/charm.json")
    expected_ids = {f"relics_addon:{relic}" for relic in ALL_RELICS}
    if charm.get("replace") is not False or set(charm.get("values", [])) != expected_ids or len(charm.get("values", [])) != 6:
        fail("charm tag must contain exactly the six shields and hives")
    report["gameplay"] = {"recipes": 6, "advancements": 6, "charm_entries": 6, "legacy_drone_recipes": 0}


def validate_registry_sources(report: dict) -> None:
    """Keep registry removal and the startup GameTest coverage coupled to this asset audit."""
    try:
        registry = REGISTRY_SOURCE.read_text(encoding="utf-8")
        game_tests = {path.name: path.read_text(encoding="utf-8") for path in REGISTRY_GAME_TESTS}
    except OSError as error:
        fail(f"unable to read registry source or startup GameTest: {error}")
    legacy_pattern = re.compile(r"\b(?:RF|MANA|TWINS)_DRONE\b|\b(?:rf|mana|twins)_drone\b")
    if legacy_pattern.search(registry):
        fail("ModItems still declares or registers a standalone drone item")
    registered = set(re.findall(r"public\s+static\s+final\s+DeferredItem<[^>]+>\s+(\w+)\s*=", registry))
    expected = {"RF_SHIELD", "MANA_SHIELD", "TWINS_SHIELD", "RF_HIVE", "MANA_HIVE", "TWINS_HIVE"}
    if registered != expected:
        fail(f"ModItems must expose exactly six inventory items; found {sorted(registered)}")
    acquisition = game_tests["AcquisitionGameTests.java"]
    shield_cells = game_tests["ShieldCellGameTests.java"]
    if "creativeTabContainsSixPlayableRelics" not in acquisition or "getDisplayItems().size() == 6" not in acquisition:
        fail("startup GameTest no longer asserts the six-item creative inventory")
    if "BuiltInRegistries.ITEM.containsKey" not in acquisition or not all(f'"{kind}_drone"' in acquisition for kind in ("rf", "mana", "twins")):
        fail("startup GameTest no longer checks the absence of standalone drone IDs")
    if "itemBackedRelicsUseCharm" not in shield_cells or "ModItems.ITEMS.getEntries()" not in shield_cells:
        fail("startup GameTest no longer enumerates every registered item")
    report["item_registry"] = {
        "registered_items": sorted(registered),
        "standalone_drone_ids": 0,
        "startup_gametest_source": "present",
    }


def validate_swarm_capacity_sources(report: dict) -> None:
    """Keep the 12-to-250 server contract and the 750-model client cap visible to release validation."""
    try:
        hive_type = HIVE_TYPE_SOURCE.read_text(encoding="utf-8")
        hive_state = HIVE_STATE_SOURCE.read_text(encoding="utf-8")
        renderer = HIVE_RENDERER_SOURCE.read_text(encoding="utf-8")
    except OSError as error:
        fail(f"unable to read swarm capacity source: {error}")
    if not re.search(r"MAX_DRONES\s*=\s*250\b", hive_type):
        fail("HiveType must cap each type at 250 drones")
    for kind in ("RF", "MANA", "TWINS"):
        if not re.search(rf"\b{kind}\s*\([^\n]*,\s*12\s*,", hive_type):
            fail(f"HiveType {kind} must start with 12 drones")
    if "HiveType.MAX_DRONES - 1" not in hive_state or "MAX_SHOTS = 100" not in hive_state:
        fail("Hive combat packets must encode units 0..249 while keeping the 100-shot bound")
    if not re.search(r"MODEL_BUDGET\s*=\s*750\b", renderer):
        fail("HiveVisualRenderer must use the 750-model global visual budget")
    report["swarm_capacity"] = {
        "initial_per_type": 12,
        "max_per_type": 250,
        "max_all_types_per_owner": 750,
        "global_model_budget": 750,
        "dense_lod_face_limit": DENSE_SWARM_FACE_LIMIT,
        "shot_cap": 100,
    }


def reader_names(reader: Reader, prefix: str) -> list[str]:
    if isinstance(reader, DirectoryReader):
        folder = reader.root / prefix
        return [path.relative_to(reader.root).as_posix() for path in folder.glob("*") if path.is_file()]
    return [name for name in reader.names if name.startswith(prefix)]


def audit(reader: Reader) -> dict:
    report: dict = {"version": MOD_VERSION}
    validate_hive_models(reader, report)
    validate_cards(reader, report)
    validate_localization(reader, report)
    validate_gameplay(reader, report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", nargs="?", type=Path, default=DEFAULT_JAR, help="final defender-hives jar path")
    parser.add_argument("--resources-only", action="store_true", help="audit source resources without opening a JAR")
    args = parser.parse_args()
    try:
        report = audit(DirectoryReader(SOURCE))
        validate_registry_sources(report)
        validate_swarm_capacity_sources(report)
        report["source_resources"] = "passed"
        if not args.resources_only:
            if not args.jar.is_file():
                fail(f"JAR is not ready: {args.jar}; use --resources-only before the parent build")
            with zipfile.ZipFile(args.jar) as archive:
                if any("/gametest/" in name for name in archive.namelist()):
                    fail("development GameTest classes are included in the production JAR")
                jar_report = audit(ArchiveReader(archive))
            report["jar"] = {"path": str(args.jar), "sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(), "audit": jar_report}
        else:
            report["jar"] = {"status": "not_checked"}
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Hive asset validation failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
