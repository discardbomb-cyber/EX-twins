#!/usr/bin/env node
// Blockstates, models, loot tables, tags, recipes and recipe-book advancements for the ship shield
// devices (three families x generator, dock and emitter drone). Recipes come in two flavours: with
// Create's casings and precision mechanism (condition neoforge:mod_loaded create) and a basic one
// without it. Run after changing any of them:
//   node tools/generate_ship_shield_data.mjs
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const ASSETS = join(ROOT, "src/main/resources/assets/relics_addon");
const DATA = join(ROOT, "src/main/resources/data");
const MOD = "relics_addon";
const FAMILIES = ["rf", "mana", "twins"];

function write(path, json) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(json, null, 2) + "\n");
}

const item = id => ({ item: id.includes(":") ? id : `${MOD}:${id}` });
const CREATE_LOADED = [{ type: "neoforge:mod_loaded", modid: "create" }];
const CREATE_MISSING = [{ type: "neoforge:not", value: { type: "neoforge:mod_loaded", modid: "create" } }];

// --- blocks -------------------------------------------------------------------------------------

const FACINGS = { north: 0, east: 90, south: 180, west: 270 };
for (const family of FAMILIES) {
  for (const block of [`${family}_ship_shield_generator`, `${family}_drone_dock`]) {
    const variants = {};
    for (const [facing, y] of Object.entries(FACINGS)) {
      for (const lit of [false, true]) {
        const variant = { model: `${MOD}:block/${block}${lit ? "_on" : ""}` };
        if (y) variant.y = y;
        variants[`facing=${facing},lit=${lit}`] = variant;
      }
    }
    write(join(ASSETS, `blockstates/${block}.json`), { variants });
    for (const lit of [false, true]) {
      write(join(ASSETS, `models/block/${block}${lit ? "_on" : ""}.json`), {
        parent: "minecraft:block/cube_bottom_top",
        textures: {
          top: `${MOD}:block/ship/${block}_top${lit ? "_on" : ""}`,
          side: `${MOD}:block/ship/${block}_side`,
          bottom: `${MOD}:block/ship/${block}_bottom`,
        },
      });
    }
    write(join(ASSETS, `models/item/${block}.json`), { parent: `${MOD}:block/${block}` });
    write(join(DATA, `${MOD}/loot_table/blocks/${block}.json`), {
      type: "minecraft:block",
      pools: [{
        rolls: 1,
        bonus_rolls: 0,
        entries: [{
          type: "minecraft:item",
          name: `${MOD}:${block}`,
          functions: [{ function: "minecraft:copy_components", source: "block_entity" }],
        }],
        conditions: [{ condition: "minecraft:survives_explosion" }],
      }],
    });
  }
  write(join(ASSETS, `models/item/${family}_emitter_drone.json`), {
    parent: "minecraft:item/generated",
    textures: { layer0: `${MOD}:item/component/${family}_emitter_drone` },
  });
}

const blocks = FAMILIES.flatMap(family => [`${MOD}:${family}_ship_shield_generator`, `${MOD}:${family}_drone_dock`]);
write(join(DATA, "minecraft/tags/block/mineable/pickaxe.json"), { replace: false, values: blocks });
write(join(DATA, "minecraft/tags/block/needs_stone_tool.json"), { replace: false, values: blocks });

// --- recipes ------------------------------------------------------------------------------------

/** One shaped recipe plus the advancement that unlocks it in the recipe book once the player holds {@code unlock}. */
function recipe(id, pattern, key, result, count, unlock, conditions) {
  const json = {
    type: "minecraft:crafting_shaped",
    category: "misc",
    pattern,
    key: Object.fromEntries(Object.entries(key).map(([symbol, ingredient]) => [symbol, item(ingredient)])),
    result: { id: `${MOD}:${result}`, count },
  };
  if (conditions) json["neoforge:conditions"] = conditions;
  write(join(DATA, `${MOD}/recipe/${id}.json`), json);
  const advancement = {
    parent: "minecraft:recipes/root",
    criteria: {
      has_ingredient: { trigger: "minecraft:inventory_changed", conditions: { items: [{ items: `${MOD}:${unlock}` }] } },
      has_the_recipe: { trigger: "minecraft:recipe_unlocked", conditions: { recipe: `${MOD}:${id}` } },
    },
    requirements: [["has_ingredient", "has_the_recipe"]],
    rewards: { recipes: [`${MOD}:${id}`] },
  };
  if (conditions) advancement["neoforge:conditions"] = conditions;
  write(join(DATA, `${MOD}/advancement/recipes/${id}.json`), advancement);
}

const CASING = "create:brass_casing", ANDESITE = "create:andesite_casing", MECHANISM = "create:precision_mechanism";
const X = "resonant_circuit";

// Generators: the family's shield core in the middle, its cells above, Create's casings and mechanism
// or plain metal blocks and circuits below.
recipe("rf_ship_shield_generator", ["ELE", "CKC", "RPR"],
  { E: "energy_cell", L: "minecraft:lightning_rod", C: CASING, K: "rf_shield_core", R: "minecraft:redstone_block", P: MECHANISM },
  "rf_ship_shield_generator", 1, "rf_shield_core", CREATE_LOADED);
recipe("rf_ship_shield_generator_basic", ["ELE", "IKI", "XRX"],
  { E: "energy_cell", L: "minecraft:lightning_rod", I: "minecraft:iron_block", K: "rf_shield_core", X, R: "minecraft:redstone_block" },
  "rf_ship_shield_generator", 1, "rf_shield_core", CREATE_MISSING);

recipe("mana_ship_shield_generator", ["MGM", "CKC", "APA"],
  { M: "mana_cell", G: "minecraft:gold_block", C: CASING, K: "mana_shield_core", A: "minecraft:amethyst_block", P: MECHANISM },
  "mana_ship_shield_generator", 1, "mana_shield_core", CREATE_LOADED);
recipe("mana_ship_shield_generator_basic", ["MGM", "GKG", "XAX"],
  { M: "mana_cell", G: "minecraft:gold_block", K: "mana_shield_core", X, A: "minecraft:amethyst_block" },
  "mana_ship_shield_generator", 1, "mana_shield_core", CREATE_MISSING);

recipe("twins_ship_shield_generator", ["ENM", "CKC", "OPO"],
  { E: "energy_cell", N: "minecraft:end_crystal", M: "mana_cell", C: CASING, K: "twins_shield_core", O: "minecraft:crying_obsidian", P: MECHANISM },
  "twins_ship_shield_generator", 1, "twins_shield_core", CREATE_LOADED);
recipe("twins_ship_shield_generator_basic", ["ENM", "BKB", "XOX"],
  { E: "energy_cell", N: "minecraft:end_crystal", M: "mana_cell", B: "minecraft:obsidian", K: "twins_shield_core", X, O: "minecraft:crying_obsidian" },
  "twins_ship_shield_generator", 1, "twins_shield_core", CREATE_MISSING);

// Docks: four drone parts around a chest, the family's cell and a casing (or a metal block).
const PARTS = { rf: "rf_drone_frame", mana: "mana_drone_shell", twins: "twins_drone_plate" };
recipe("rf_drone_dock", ["FXF", "AHE", "FXF"], { F: PARTS.rf, X, A: ANDESITE, H: "minecraft:chest", E: "energy_cell" }, "rf_drone_dock", 1, PARTS.rf, CREATE_LOADED);
recipe("rf_drone_dock_basic", ["FXF", "IHE", "FXF"], { F: PARTS.rf, X, I: "minecraft:iron_block", H: "minecraft:chest", E: "energy_cell" }, "rf_drone_dock", 1, PARTS.rf, CREATE_MISSING);
recipe("mana_drone_dock", ["SXS", "AHM", "SXS"], { S: PARTS.mana, X, A: ANDESITE, H: "minecraft:chest", M: "mana_cell" }, "mana_drone_dock", 1, PARTS.mana, CREATE_LOADED);
recipe("mana_drone_dock_basic", ["SXS", "GHM", "SXS"], { S: PARTS.mana, X, G: "minecraft:gold_block", H: "minecraft:chest", M: "mana_cell" }, "mana_drone_dock", 1, PARTS.mana, CREATE_MISSING);
recipe("twins_drone_dock", ["TXT", "EHM", "TCT"], { T: PARTS.twins, X, E: "energy_cell", H: "minecraft:chest", M: "mana_cell", C: CASING }, "twins_drone_dock", 1, PARTS.twins, CREATE_LOADED);
recipe("twins_drone_dock_basic", ["TXT", "EHM", "TBT"], { T: PARTS.twins, X, E: "energy_cell", H: "minecraft:chest", M: "mana_cell", B: "minecraft:obsidian" }, "twins_drone_dock", 1, PARTS.twins, CREATE_MISSING);

// Emitter drones, four at a time, the same with or without Create.
for (const family of FAMILIES) {
  recipe(`${family}_emitter_drone`, [" S ", "GXG", " C "], { S: PARTS[family], G: "minecraft:glass", X, C: "minecraft:copper_ingot" },
    `${family}_emitter_drone`, 4, PARTS[family], null);
}

console.log("ship shield data written");
