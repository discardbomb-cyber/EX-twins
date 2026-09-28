/** Direct native-grid pixel art for the three Relics hive ability cards. */
import { deflateSync } from "node:zlib";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const DESTINATION = resolve(ROOT, "src/main/resources/assets/relics/textures/abilities");
const PREVIEW = resolve(ROOT, "art/research/hive-cards-preview.png");
const PALETTES = {
  rf_hive: ["07151f", "12394b", "1b718a", "36d5eb", "dcffff"],
  mana_hive: ["082020", "145351", "28968a", "5ce3c7", "ffe18b"],
  twins_hive: ["100915", "2c1738", "57256e", "a84ed2", "f0c2ff"],
};

const crcTable = Array.from({ length: 256 }, (_, seed) => {
  let value = seed;
  for (let bit = 0; bit < 8; bit += 1) value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0);
  return value >>> 0;
});
const crc32 = (bytes) => {
  let value = 0xffffffff;
  for (const byte of bytes) value = crcTable[(value ^ byte) & 255] ^ (value >>> 8);
  return (value ^ 0xffffffff) >>> 0;
};
const chunk = (type, data) => {
  const name = Buffer.from(type);
  const output = Buffer.alloc(data.length + 12);
  output.writeUInt32BE(data.length, 0);
  name.copy(output, 4);
  data.copy(output, 8);
  output.writeUInt32BE(crc32(Buffer.concat([name, data])), output.length - 4);
  return output;
};

function savePng(path, image) {
  const scanlines = Buffer.alloc((image.width * 3 + 1) * image.height);
  for (let y = 0; y < image.height; y += 1) {
    const row = y * (image.width * 3 + 1);
    scanlines[row] = 0;
    image.pixels.copy(scanlines, row + 1, y * image.width * 3, (y + 1) * image.width * 3);
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(image.width, 0);
  header.writeUInt32BE(image.height, 4);
  header[8] = 8;
  header[9] = 2;
  const png = Buffer.concat([Buffer.from("89504e470d0a1a0a", "hex"), chunk("IHDR", header), chunk("IDAT", deflateSync(scanlines)), chunk("IEND", Buffer.alloc(0))]);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, png);
}

function canvas(width, height, color) {
  const pixels = Buffer.alloc(width * height * 3);
  const image = { width, height, pixels };
  fill(image, 0, 0, width - 1, height - 1, color);
  return image;
}
function rgb(hex) { return [parseInt(hex.slice(0, 2), 16), parseInt(hex.slice(2, 4), 16), parseInt(hex.slice(4, 6), 16)]; }
function point(image, x, y, color) {
  if (x < 0 || y < 0 || x >= image.width || y >= image.height) return;
  const offset = (y * image.width + x) * 3;
  image.pixels.set(rgb(color), offset);
}
function fill(image, left, top, right, bottom, color) {
  for (let y = top; y <= bottom; y += 1) for (let x = left; x <= right; x += 1) point(image, x, y, color);
}
function line(image, x0, y0, x1, y1, color) {
  let dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
  let dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1, error = dx + dy;
  while (true) {
    point(image, x0, y0, color);
    if (x0 === x1 && y0 === y1) return;
    const twice = error * 2;
    if (twice >= dy) { error += dy; x0 += sx; }
    if (twice <= dx) { error += dx; y0 += sy; }
  }
}
function polyline(image, points, color) {
  for (let index = 1; index < points.length; index += 1) line(image, ...points[index - 1], ...points[index], color);
}
function drone(image, x, y, colors, wing = 2) {
  const [edge, shadow, body, light, white] = colors;
  fill(image, x - 2, y - 2, x + 2, y + 2, edge);
  fill(image, x - 1, y - 1, x + 1, y + 1, body);
  point(image, x, y - 1, white); point(image, x + 1, y, light);
  point(image, x - wing, y, shadow); point(image, x + wing, y, shadow);
}
function border(image, colors) {
  for (let x = 1; x < 21; x += 1) { point(image, x, 1, colors[1]); point(image, x, 29, colors[1]); }
  for (let y = 2; y < 29; y += 1) { point(image, 1, y, colors[1]); point(image, 20, y, colors[1]); }
}
function drawRf(image, colors) {
  const [edge, shadow, body, light, white] = colors;
  fill(image, 7, 10, 14, 21, edge); fill(image, 8, 11, 13, 20, body); fill(image, 9, 12, 12, 19, shadow);
  polyline(image, [[10, 12], [12, 12], [12, 16], [10, 16], [10, 19]], light); point(image, 11, 14, white);
  for (const [x, y] of [[3, 7], [18, 7], [3, 16], [18, 16], [5, 26], [16, 26], [11, 4]]) { drone(image, x, y, colors); line(image, 11, 16, x, y, shadow); }
  point(image, 5, 5, light); point(image, 16, 5, light);
}
function drawMana(image, colors) {
  const [edge, shadow, body, light, gold] = colors;
  for (let y = 5; y <= 26; y += 1) { const half = Math.max(1, Math.round(5 - Math.abs(15 - y) / 3)); fill(image, 11 - half, y, 11 + half, y, edge); }
  for (let y = 7; y <= 23; y += 1) { const half = Math.max(1, Math.round(3 - Math.abs(15 - y) / 4)); fill(image, 11 - half, y, 11 + half, y, body); }
  polyline(image, [[11, 8], [10, 14], [11, 20]], light); point(image, 11, 12, gold); fill(image, 10, 14, 11, 19, shadow);
  for (const [x, y] of [[4, 9], [18, 9], [3, 19], [19, 19], [7, 28], [15, 28]]) { drone(image, x, y, colors, 1); line(image, 11, 16, x, y, shadow); }
  point(image, 2, 4, gold); point(image, 20, 5, light);
}
function orb(image, cx, cy, colors) {
  const [edge, shadow, body, light, white] = colors;
  for (let y = -4; y <= 4; y += 1) for (let x = -4; x <= 4; x += 1) if (x * x + y * y <= 16) point(image, cx + x, cy + y, edge);
  for (let y = -3; y <= 3; y += 1) for (let x = -3; x <= 3; x += 1) if (x * x + y * y <= 9) point(image, cx + x, cy + y, body);
  fill(image, cx - 1, cy - 2, cx + 1, cy + 2, shadow); point(image, cx, cy - 2, white); point(image, cx + 2, cy, light);
}
function drawTwins(image, colors) {
  const [, shadow, , light, white] = colors;
  orb(image, 8, 13, colors); orb(image, 14, 19, colors); polyline(image, [[8, 13], [11, 16], [14, 19]], light);
  for (const [x, y] of [[3, 6], [18, 6], [2, 23], [19, 24], [9, 29], [16, 29]]) { drone(image, x, y, colors, 1); line(image, 11, 16, x, y, shadow); }
  point(image, 10, 4, light); point(image, 12, 4, white);
}

const DRAWERS = { rf_hive: drawRf, mana_hive: drawMana, twins_hive: drawTwins };
const cards = Object.entries(DRAWERS).map(([hive, drawer]) => {
  const image = canvas(22, 31, PALETTES[hive][0]);
  border(image, PALETTES[hive]); drawer(image, PALETTES[hive]);
  savePng(resolve(DESTINATION, hive, `${hive}.png`), image);
  return image;
});
const preview = canvas(480, 270, "171b22");
cards.forEach((card, index) => {
  const offsetX = 20 + index * 155;
  for (let y = 0; y < card.height; y += 1) for (let x = 0; x < card.width; x += 1) {
    const color = card.pixels.subarray((y * card.width + x) * 3, (y * card.width + x + 1) * 3);
    for (let scaleY = 0; scaleY < 6; scaleY += 1) for (let scaleX = 0; scaleX < 6; scaleX += 1) {
      const target = ((54 + y * 6 + scaleY) * preview.width + offsetX + x * 6 + scaleX) * 3;
      preview.pixels.set(color, target);
    }
  }
});
savePng(PREVIEW, preview);
