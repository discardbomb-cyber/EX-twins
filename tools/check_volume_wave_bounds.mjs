import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Exhaustively exercise float-rounded wave distances and noise endpoints.
// The reference is the original shader's support predicate, before radial rejection.
const f = Math.fround;
let checked = 0, rejected = 0;
for (const name of ['armageddon_volume', 'mana_volume']) {
    const source = readFileSync(new URL(`../src/main/resources/assets/relics_addon/shaders/core/${name}.fsh`, import.meta.url), 'utf8');
    assert.match(source, /return sum \/ 0\.875;/, `${name}: noise bound needs revisiting if fbm changes`);
    assert.match(source, /Front - r > -22\.0 && Front - r < 84\.0/);
    assert.match(source, /float s = Front - \(r \+ \(n - 0\.5\) \* 26\.0\);/);
    for (const front of [1.01, 18, 90, 135, 256, 4096]) {
        for (let delta = -40; delta <= 100; delta += 0.125) {
            const r = f(f(front) - f(delta));
            if (r < 0) continue;
            const kept = f(f(front) - r) > -22 && f(f(front) - r) < 84;
            for (let k = 0; k <= 256; k++) {
                const noise = f(k / 256);
                const s = f(f(front) - f(r + f(f(noise - 0.5) * 26)));
                const contributed = s > -8 && s < 70;
                assert.ok(kept || !contributed, `${name}: clipped contribution at ${front}, ${r}, ${noise}`);
                checked++;
                if (!kept) rejected++;
            }
        }
    }
}
console.log(`Wave support: ${checked} float cases, ${rejected} safe noise skips; no contributions clipped`);
