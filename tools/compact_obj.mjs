#!/usr/bin/env node
// Lossless OBJ compaction: drops repeated `v` / `vt` / `vn` records (exact-string duplicates, first
// occurrence kept in place) and renumbers face indices, each record type independently. Everything
// else - face order and winding, groups and objects, usemtl / mtllib / s lines, comments, the
// numeric text of every record and the file's line endings - is written back unchanged, so the
// baked model is the same to the last vertex.
//
//   node tools/compact_obj.mjs [--dry-run] [--verify] [file.obj | dir ...]
//       Compacts the files in place (default: every .obj under assets/relics_addon/models) and
//       prints a before/after table. --dry-run only prints the table; --verify fails (exit 1) when
//       a file is not already compact.
//   node tools/compact_obj.mjs --check before.obj after.obj
//       Expands both files' faces into full tuples (position, uv and normal as text, plus the
//       group, object, material and smoothing group in force) and compares their digests.
//
// Generators import { compactObjText } and write its result directly.

import { createHash } from "node:crypto";
import { readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const RECORD_TYPES = ["v", "vt", "vn"];
/** Statements that carry vertex indices: which record type each slot of an `a/b/c` reference uses. */
const INDEXED = { f: ["v", "vt", "vn"], l: ["v", "vt"], p: ["v"] };
const UNSUPPORTED = new Set(["curv", "curv2", "surf", "vp", "trim", "hole", "scrv", "sp"]);

function keyword(line) {
  const end = line.search(/\s|$/);
  return line.slice(0, end);
}

/** Splits a face-like statement into its references, each a list of index strings per slash-separated slot. */
function references(line) {
  return line.trim().split(/\s+/).slice(1).map(token => token.split("/"));
}

/** Resolves a (possibly negative, relative) 1-based OBJ index against the number of records seen so far. */
function absolute(text, seen, line) {
  if (text === "") return 0;
  const index = Number(text);
  if (!Number.isInteger(index) || index === 0) throw new Error(`bad index '${text}' in: ${line}`);
  const result = index < 0 ? seen + 1 + index : index;
  if (result < 1 || result > seen) throw new Error(`index ${text} out of range (${seen} records) in: ${line}`);
  return result;
}

/**
 * Compacts OBJ text. Returns the new text and per-type record counts before and after, plus face
 * and statement counts; the text is identical to the input when nothing was duplicated.
 */
export function compactObjText(text) {
  const eol = text.includes("\r\n") ? "\r\n" : "\n";
  const lines = text.split(/\r?\n/);
  const first = Object.fromEntries(RECORD_TYPES.map(type => [type, new Map()])); // line text -> new index
  const remap = Object.fromEntries(RECORD_TYPES.map(type => [type, [0]]));       // old index -> new index
  const output = [];
  let faces = 0;
  for (const line of lines) {
    const word = keyword(line);
    if (UNSUPPORTED.has(word)) throw new Error(`unsupported statement '${word}': ${line}`);
    if (RECORD_TYPES.includes(word)) {
      const table = first[word];
      let index = table.get(line);
      if (index === undefined) {
        index = table.size + 1;
        table.set(line, index);
        output.push(line);
      }
      remap[word].push(index);
      continue;
    }
    const slots = INDEXED[word];
    if (!slots) {
      output.push(line);
      continue;
    }
    if (word === "f") faces++;
    const rewritten = references(line).map(parts => {
      if (parts.length > slots.length) throw new Error(`too many index slots in: ${line}`);
      return parts.map((part, slot) => {
        if (part === "") return "";
        const type = slots[slot];
        return String(remap[type][absolute(part, remap[type].length - 1, line)]);
      }).join("/");
    });
    output.push(`${word} ${rewritten.join(" ")}`);
  }
  const counts = Object.fromEntries(RECORD_TYPES.map(type => [type, { before: remap[type].length - 1, after: first[type].size }]));
  return { text: output.join(eol), counts, faces };
}

/**
 * The model as its baker sees it: every face expanded to full record text with the group, object,
 * material and smoothing group in force, and every other statement kept as written. Comments and
 * blank lines are ignored. Returns a digest plus counts.
 */
export function expandObj(text) {
  const records = Object.fromEntries(RECORD_TYPES.map(type => [type, [null]]));
  const hash = createHash("sha256");
  const state = { g: "", o: "", usemtl: "", s: "" };
  let faces = 0, statements = 0;
  for (const line of text.split(/\r?\n/)) {
    const word = keyword(line);
    if (word === "" || word.startsWith("#")) continue;
    if (UNSUPPORTED.has(word)) throw new Error(`unsupported statement '${word}': ${line}`);
    if (RECORD_TYPES.includes(word)) { records[word].push(line); continue; }
    if (word in state) state[word] = line.trim();
    const slots = INDEXED[word];
    if (!slots) { hash.update(`${line.trim()}\n`); statements++; continue; }
    if (word === "f") faces++;
    const tuple = references(line).map(parts => parts.map((part, slot) => {
      if (part === "") return "";
      const type = slots[slot];
      return records[type][absolute(part, records[type].length - 1, line)];
    }).join("\u0001")).join("\u0002");
    hash.update(`${word}|${state.g}|${state.o}|${state.usemtl}|${state.s}|${tuple}\n`);
  }
  return { digest: hash.digest("hex"), faces, statements };
}

function objFiles(paths) {
  const found = [];
  const walk = path => {
    if (statSync(path).isDirectory()) {
      for (const entry of readdirSync(path).sort()) walk(join(path, entry));
    } else if (path.toLowerCase().endsWith(".obj")) {
      found.push(path);
    }
  };
  for (const path of paths) walk(path);
  return found;
}

function check(before, after) {
  const a = expandObj(readFileSync(before, "utf8"));
  const b = expandObj(readFileSync(after, "utf8"));
  const same = a.digest === b.digest && a.faces === b.faces;
  console.log(`${same ? "SAME" : "DIFFERENT"}: ${before} (${a.faces} faces, ${a.digest.slice(0, 16)}) vs ${after} (${b.faces} faces, ${b.digest.slice(0, 16)})`);
  return same;
}

function main(argv) {
  const flags = new Set(argv.filter(arg => arg.startsWith("--")));
  const paths = argv.filter(arg => !arg.startsWith("--"));
  if (flags.has("--check")) {
    if (paths.length !== 2) throw new Error("--check takes exactly two files");
    process.exit(check(paths[0], paths[1]) ? 0 : 1);
  }
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  const files = objFiles(paths.length ? paths : [join(root, "src/main/resources/assets/relics_addon/models")]);
  const dryRun = flags.has("--dry-run"), verify = flags.has("--verify");
  const rows = [];
  const total = { before: 0, after: 0, records: 0, compact: 0 };
  let stale = 0;
  for (const file of files) {
    const text = readFileSync(file, "utf8");
    const { text: compacted, counts, faces } = compactObjText(text);
    const before = Buffer.byteLength(text), after = Buffer.byteLength(compacted);
    const changed = compacted !== text;
    if (changed) stale++;
    if (changed && !dryRun && !verify) {
      if (expandObj(text).digest !== expandObj(compacted).digest) throw new Error(`compaction changed the expanded mesh of ${file}`);
      writeFileSync(file, compacted);
    }
    const sum = key => RECORD_TYPES.reduce((n, type) => n + counts[type][key], 0);
    total.before += before; total.after += after; total.records += sum("before"); total.compact += sum("after");
    rows.push([relative(root, file).replace(/\\/g, "/"), before, after, `${counts.v.before}/${counts.vt.before}/${counts.vn.before}`,
      `${counts.v.after}/${counts.vt.after}/${counts.vn.after}`, faces, changed ? (verify ? "NOT COMPACT" : "compacted") : "already compact"]);
  }
  rows.push(["total", total.before, total.after, total.records, total.compact, "", `-${(100 - 100 * total.after / Math.max(1, total.before)).toFixed(1)}%`]);
  const header = ["file", "bytes before", "bytes after", "v/vt/vn before", "v/vt/vn after", "faces", "status"];
  console.log(`| ${header.join(" | ")} |\n|${header.map(() => "---").join("|")}|`);
  for (const row of rows) console.log(`| ${row.join(" | ")} |`);
  if (verify && stale) {
    console.error(`${stale} OBJ file(s) are not compact; run node tools/compact_obj.mjs`);
    process.exit(1);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main(process.argv.slice(2));
