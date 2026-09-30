#version 150

// Mana Armageddon's surfaces, found exactly along each view ray at full resolution and drawn in two passes round the
// haze marched in mana_volume: the far side (Side 0) before it, the near side (Side 1) after it, so the haze lies
// between. The far pass holds the back of the sphere of runes, the sun inside it, the seal on the ground and the
// column's far wall; the near pass the front of the sphere, the column's near wall and the crescent moon in the
// white sky. Runes come from the Mana rune atlas (Sampler0). Everything keeps to the world's own axes, so nothing
// turns as the camera turns, and the world's depth hides whatever stands behind the world. Drawn with
// premultiplied alpha: light added, what lies behind dimmed by what covers it.
uniform sampler2D Sampler0;   // the Mana runes: 8x4 cells, 28 glyphs then rings, gear, curl, bead
uniform sampler2D Sampler1;   // depth of the scene
uniform mat4 InverseProj;
uniform vec2 TargetSize;
uniform vec3 Centre;          // where the streams met, on the ground, in view space
uniform vec3 Up;              // the world's up, in view space
uniform vec3 East;            // the world's east (+x), in view space
uniform vec3 North;           // the world's south (+z), in view space
uniform vec3 Seam;            // level, from the centre towards where the shot came from, in view space
uniform float Time;           // ticks since the burst (negative before it)
uniform float PixelAngle;
uniform float Side;           // 0: the far side, 1: the near side
uniform float SphereRadius;   // the sphere of runes (a dome on the seal), 0 when there is none
uniform float SphereWritten;  // how far its running wave of runes has got, 0..1
uniform float SpherePressure; // how hard the sun presses from inside: runes whiten and light cracks through them
uniform float SphereGlow;
uniform float SphereTurn;     // how far its bands have turned
uniform float SunRadius;
uniform float SunGlow;
uniform float SunLift;        // the sun's height over the centre
uniform float SealRadius;     // the seal on the ground, 0 when there is none
uniform float SealGlow;
uniform float SealWave;       // how far out the wave lighting its beads has run
uniform float SealTurn;
uniform float ColumnRadius;   // the column of light, 0 when there is none
uniform float ColumnGlow;
uniform float ColumnTop;
uniform float ColumnRise;     // how far its runes have risen, in their own cells
uniform vec3 MoonAt;          // the crescent moon, in view space
uniform vec3 MoonLight;       // where its light comes from, in view space
uniform float MoonRadius;
uniform float MoonGlow;

in vec3 viewPos;

out vec4 fragColor;

const float PI = 3.14159265;
const vec3 TURQUOISE = vec3(0.31, 0.95, 0.85);
const vec3 GOLD = vec3(1.0, 0.8, 0.42);
const vec3 SEAL_BLUE = vec3(0.36, 0.78, 1.0);

const int MAX_LAYERS = 6;
float layerAt[MAX_LAYERS];
vec4 layerColour[MAX_LAYERS];
int layers = 0;

void addLayer(float at, vec3 light, float cover) {
    if (layers < MAX_LAYERS) {
        layerAt[layers] = at;
        layerColour[layers] = vec4(light, clamp(cover, 0.0, 1.0));
        layers++;
    }
}

float hash1(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float hash3(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash3(i), hash3(i + vec3(1.0, 0.0, 0.0)), f.x),
                   mix(hash3(i + vec3(0.0, 1.0, 0.0)), hash3(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
               mix(mix(hash3(i + vec3(0.0, 0.0, 1.0)), hash3(i + vec3(1.0, 0.0, 1.0)), f.x),
                   mix(hash3(i + vec3(0.0, 1.0, 1.0)), hash3(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
}

float sceneDistance(vec2 uv) {
    float depth = texture(Sampler1, uv).r;
    if (depth >= 1.0) {
        return 1.0e6;
    }
    vec4 point = InverseProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return length(point.xyz / point.w);
}

// The ink of atlas cell {@code cell} at {@code q} across it (0..1, y down), sampled with the given gradients of q.
float glyph(float cell, vec2 q, vec2 dx, vec2 dy) {
    if (q.x < 0.0 || q.y < 0.0 || q.x > 1.0 || q.y > 1.0) {
        return 0.0;
    }
    vec2 origin = vec2(mod(cell, 8.0), floor(cell / 8.0));
    vec2 scale = vec2(1.0 / 8.0, 1.0 / 4.0);
    return textureGrad(Sampler0, (origin + q) * scale, dx * scale, dy * scale).a;
}

// An antialiased line across a coordinate: {@code d} from its middle, {@code width} wide, {@code fw} the coordinate's change across a pixel.
float stroke(float d, float width, float fw) {
    float w = max(width, fw);
    return (1.0 - smoothstep(w * 0.5, w * 0.5 + fw, abs(d))) * min(1.0, width / max(fw, 1.0e-4));
}

// The angle round Up of a direction in world axes, and a copy of it split on the other side, so its change across
// a pixel never jumps where the angle wraps.
vec2 angles(vec3 w) {
    return vec2(atan(w.z, w.x), atan(-w.z, -w.x));
}

float angleWidth(vec2 a) {
    return min(fwidth(a.x), fwidth(a.y));
}

void main() {
    vec2 uv = gl_FragCoord.xy / TargetSize;
    vec3 dir = normalize(viewPos);
    float scene = sceneDistance(uv);
    vec3 seamRight = normalize(cross(Seam, Up));
    bool far = Side < 0.5;

    // --- Where the ray meets each surface, worked out for every pixel (so the gradients the runes are sampled
    // with are taken outside any branch). ---
    // The sphere: its near and far points.
    float b = dot(Centre, dir), closest2 = dot(Centre, Centre) - b * b, r2 = SphereRadius * SphereRadius;
    float half_ = sqrt(max(r2 - closest2, 0.0));
    float sphereT = far ? b + half_ : b - half_;
    vec3 sp = dir * sphereT - Centre;
    vec3 sn = sp / max(SphereRadius, 0.001);
    vec3 sw = vec3(dot(sn, East), dot(sn, Up), dot(sn, North));
    vec2 sa = angles(sw);
    float lat = asin(clamp(sw.y, -1.0, 1.0));
    float latW = fwidth(lat), lonW = angleWidth(sa);
    // Its bands' own latitudes and longitudes.
    vec3 bandAxis = normalize(Up * cos(0.26) + seamRight * sin(0.26));
    vec3 bandU = normalize(cross(bandAxis, Seam)), bandV = cross(bandAxis, bandU);
    float bandLat = dot(sn, bandAxis);
    vec2 bandA = vec2(atan(dot(sn, bandV), dot(sn, bandU)), atan(-dot(sn, bandV), -dot(sn, bandU)));
    float bandLatW = fwidth(bandLat), bandLonW = angleWidth(bandA);
    vec3 thinAxis = normalize(Up * cos(1.05) + Seam * sin(1.05));
    float thinLat = dot(sn, thinAxis), thinLatW = fwidth(thinLat);
    float seamSide = dot(sn, seamRight), seamSideW = fwidth(seamSide);

    // The seal: the level plane a little over the centre.
    float along = dot(dir, Up);
    float sealT = abs(along) > 1.0e-4 ? (0.25 + dot(Centre, Up)) / along : -1.0;
    vec3 gp = dir * sealT - Centre;
    vec3 gw = vec3(dot(gp, East), dot(gp, Up), dot(gp, North));
    float gr = length(gw.xz);
    vec2 ga = angles(gw);
    float grW = fwidth(gr), gaW = angleWidth(ga);

    // The column: a level circle round the centre, drawn up into the sky.
    vec3 dh = dir - Up * along, ch = -Centre - Up * dot(-Centre, Up);
    float ca = dot(dh, dh), cb = dot(dh, ch), cc = dot(ch, ch) - ColumnRadius * ColumnRadius, cd = cb * cb - ca * cc;
    float columnT = ca > 1.0e-6 && cd > 0.0 ? (far ? (-cb + sqrt(cd)) / ca : (-cb - sqrt(cd)) / ca) : -1.0;
    vec3 cp = dir * columnT - Centre;
    vec3 cw = vec3(dot(cp, East), dot(cp, Up), dot(cp, North));
    vec2 cA = angles(cw);
    float cellsRound = 64.0, cell = 2.0 * PI * max(ColumnRadius, 0.5) / cellsRound;
    vec2 cg = vec2(cA.x / (2.0 * PI) * cellsRound, cw.y / cell - ColumnRise);
    vec2 cgx = vec2(angleWidth(cA) / (2.0 * PI) * cellsRound, fwidth(cw.y) / cell);
    vec2 cgdx = vec2(min(abs(dFdx(cA.x)), abs(dFdx(cA.y))) / (2.0 * PI) * cellsRound, dFdx(cw.y) / cell);
    vec2 cgdy = vec2(min(abs(dFdy(cA.x)), abs(dFdy(cA.y))) / (2.0 * PI) * cellsRound, dFdy(cw.y) / cell);

    // --- The sphere of runes: a translucent dome on the seal, turquoise on the half to the left of its seam and gold on
    // the right, its runes written in a running wave, its bands turning against each other; whitening and cracking as
    // the sun presses. ---
    if (SphereRadius > 0.05 && SphereGlow > 0.001 && closest2 < r2 && sphereT > 0.05 && sphereT < scene && sw.y > -0.02) {
        vec3 colour = mix(TURQUOISE, GOLD, smoothstep(-0.08, 0.08, seamSide));
        colour = mix(colour, vec3(1.0), SpherePressure * 0.8);
        float rim = pow(1.0 - abs(dot(sn, -dir)), 3.0);
        vec3 light = colour * (0.03 + 0.35 * rim);
        float ink = 0.0;
        // Rows of glyphs and ornaments from the top down, as many round each row as keeps them square.
        float rowHeight = PI * 0.5 / 7.0, rowF = (PI * 0.5 - lat) / rowHeight, row = floor(rowF);
        float count = max(3.0, floor(2.0 * PI * sin((row + 0.5) * rowHeight) / rowHeight));
        float lon = sa.x + SphereTurn * 0.25, colF = (lon / (2.0 * PI) + 0.5) * count, col = floor(colF);
        vec2 q = vec2(fract(colF), fract(rowF));
        float pick = hash1(vec2(row, col)), kind = pick < 0.72 ? floor(pick / 0.72 * 28.0) : 28.0 + floor((pick - 0.72) / 0.28 * 3.0);
        float scale = 0.72 + 0.2 * hash1(vec2(col, row + 7.0)), spin = kind < 28.0 ? (hash1(vec2(row + 3.0, col)) - 0.5) * 0.5 : hash1(vec2(row, col + 5.0)) * 6.28;
        mat2 turn = mat2(cos(spin), sin(spin), -sin(spin), cos(spin));
        vec2 local = turn * ((q - 0.5) / scale) + 0.5;
        vec2 gx = turn * vec2(lonW / (2.0 * PI) * count, 0.0) / scale, gy = turn * vec2(0.0, latW / rowHeight) / scale;
        float order = (row + col / count) / 7.0, written = clamp((SphereWritten - order * 0.9) * 12.0, 0.0, 1.0);
        float fresh = exp(-max(SphereWritten - order * 0.9, 0.0) * 14.0);
        ink = glyph(kind, local, gx, gy) * written;
        // The near side's runes stand out over the fog inside; the far side's show dimmer through it.
        light += mix(colour, vec3(1.0), (far ? 0.3 : 0.12) + 0.45 * fresh) * ink * ((far ? 1.1 : 1.8) + 1.6 * fresh);
        // The wide band round the equator, tilted, its runes turning one way; the thin band at an angle turning the other.
        float band = 0.16;
        if (abs(bandLat) < band + bandLatW) {
            float bandCount = 30.0, bf = ((bandA.x + SphereTurn) / (2.0 * PI) + 0.5) * bandCount;
            vec2 bq = vec2(fract(bf), 0.5 - bandLat / (2.0 * band) * 0.95);
            vec2 bgx = vec2(bandLonW / (2.0 * PI) * bandCount, bandLatW / (2.0 * band));
            float bInk = glyph(floor(hash1(vec2(floor(bf), 41.0)) * 28.0), bq, vec2(bgx.x, 0.0), vec2(0.0, bgx.y));
            float edges = stroke(abs(bandLat) - band, 0.012, bandLatW) + stroke(abs(bandLat) - band * 0.82, 0.006, bandLatW);
            light += mix(colour, vec3(1.0), 0.45) * (bInk * 1.5 + edges * 1.8) * smoothstep(0.0, 0.4, SphereWritten);
            ink = max(ink, bInk);
        }
        float thin = stroke(thinLat, 0.012, thinLatW) + stroke(abs(thinLat) - 0.03, 0.005, thinLatW);
        light += colour * thin * 1.6 * smoothstep(0.1, 0.6, SphereWritten);
        // The seam: a glowing meridian through the middle, facing where the shot came from.
        float seam = stroke(seamSide, 0.012, seamSideW);
        light += vec3(1.0, 0.97, 0.9) * seam * 2.4;
        // Light cracking through the glyphs as the sun presses.
        if (SpherePressure > 0.01) {
            vec3 cellP = sw * 5.0;
            vec3 base = floor(cellP);
            float first = 8.0, second = 8.0;
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                vec3 o = base + vec3(x, y, z);
                vec3 feature = o + vec3(hash3(o), hash3(o + 17.0), hash3(o + 31.0));
                float d = length(cellP - feature);
                if (d < first) { second = first; first = d; } else if (d < second) { second = d; }
            }
            float crack = 1.0 - smoothstep(0.0, 0.02 + 0.09 * SpherePressure * SpherePressure, second - first);
            light += vec3(1.0, 0.95, 0.8) * crack * smoothstep(0.2, 0.7, SpherePressure) * 3.0;
        }
        float fade = far ? 0.55 : 1.0;
        addLayer(sphereT, light * SphereGlow * fade, (0.06 + 0.25 * ink) * SphereGlow);
    }

    // --- The sun inside the sphere: a white-gold ball, its surface boiling, in a corona. ---
    if (far && SunRadius > 0.05 && SunGlow > 0.001) {
        vec3 sun = Centre + Up * SunLift;
        float sb = dot(sun, dir), sc2 = dot(sun, sun) - sb * sb, rs2 = SunRadius * SunRadius;
        // Its surface where the ray meets it, or for its corona the depth of its middle: either shows only where nothing of
        // the world stands nearer.
        float mu = sc2 < rs2 ? sqrt(1.0 - sc2 / rs2) : 0.0, surface = sc2 < rs2 ? sb - SunRadius * mu : sb;
        if (sb > 0.0 && surface < scene) {
            float closest = sqrt(max(sc2, 0.0));
            float corona = exp(-max(closest - SunRadius, 0.0) / (SunRadius * 0.7));
            vec3 light = mix(vec3(1.0, 0.85, 0.5), vec3(0.5, 1.0, 0.9), 0.3) * corona * 1.2;
            float cover = 0.0;
            if (sc2 < rs2) {
                vec3 p = dir * surface - sun;
                vec3 w = vec3(dot(p, East), dot(p, Up), dot(p, North)) / SunRadius;
                float boil = noise(w * 4.0 + vec3(0.0, Time * 0.08, 0.0));
                light += mix(vec3(1.0, 0.72, 0.35), vec3(1.0, 0.98, 0.92), mu) * (2.2 + 0.8 * boil);
                cover = 1.0;
            }
            addLayer(surface, light * SunGlow, cover * min(1.0, SunGlow));
        }
    }

    // --- The seal on the ground: rings of fine lines turning their own ways, beads on them lighting in a wave from the
    // middle out, scalloped rings, a belt of runes; cold blue, the beads glinting gold, brighter inside the column. ---
    if (far && SealRadius > 0.05 && SealGlow > 0.001 && sealT > 0.05 && sealT < scene && gr < SealRadius * 1.02) {
        float R = SealRadius, light = 0.0, gold = 0.0;
        float fr = gr / R, frW = grW / R;
        // Rings: radius (share of the seal), width (blocks), turn rate, beads round it.
        float turned = SealTurn;
        const int RINGS = 12;
        float radii[RINGS] = float[](0.06, 0.1, 0.16, 0.24, 0.3, 0.38, 0.55, 0.63, 0.72, 0.82, 0.9, 1.0);
        float widths[RINGS] = float[](0.55, 0.4, 0.35, 0.5, 0.42, 0.3, 0.5, 0.55, 0.42, 0.36, 0.55, 0.8);
        float spins[RINGS] = float[](1.0, -1.6, 0.7, -0.5, 1.2, -0.8, 0.6, -0.9, 0.45, -0.35, 0.3, -0.25);
        float beads[RINGS] = float[](0.0, 12.0, 0.0, 0.0, 24.0, 0.0, 36.0, 0.0, 0.0, 0.0, 48.0, 0.0);
        float scallops[RINGS] = float[](0.0, 0.0, 0.0, 12.0, 0.0, 0.0, 0.0, 16.0, 0.0, 0.0, 24.0, 0.0);
        for (int i = 0; i < RINGS; i++) {
            float angle = ga.x + turned * spins[i];
            float radius = radii[i] * R;
            if (scallops[i] > 0.0) {
                radius += R * 0.012 * abs(sin(angle * scallops[i] * 0.5));
            }
            // Each line with a soft glow round it, so the rings still show seen from far off and low down.
            light += max(stroke(gr - radius, widths[i], grW), 0.25 * exp(-pow((gr - radius) / (widths[i] * 3.0 + grW), 2.0)));
            if (i == 5 || i == 9 || i == 11) {
                light += stroke(gr - radius + widths[i] * 3.0, widths[i] * 0.6, grW);
            }
            if (beads[i] > 0.0) {
                float step = 2.0 * PI / beads[i], k = floor(angle / step + 0.5);
                vec2 off = vec2(gr - radii[i] * R, (angle - k * step) * gr);
                float d = length(off), size = 0.6 + R * 0.012;
                float bead = stroke(d - size, 0.3, grW) + (1.0 - smoothstep(size * 0.35, size * 0.35 + grW, d)) + 0.3 * exp(-d * d / (size * size * 2.0));
                float wave = exp(-pow((radii[i] * R - SealWave) / (R * 0.06), 2.0));
                light += bead;
                gold += bead * (0.3 + 1.7 * wave);
            }
        }
        // The belt of runes, between two of the rings, turning slowly.
        float inner = 0.44 * R, outer = 0.5 * R;
        if (gr > inner - grW && gr < outer + grW) {
            float count = 40.0, bf = ((ga.x - turned * 0.4) / (2.0 * PI) + 0.5) * count;
            vec2 bq = vec2(fract(bf), (outer - gr) / (outer - inner));
            float bink = glyph(floor(hash1(vec2(floor(bf), 13.0)) * 28.0), bq, vec2(gaW / (2.0 * PI) * count, 0.0), vec2(0.0, grW / (outer - inner)));
            light += bink * 1.4;
        }
        light += stroke(gr - inner, 0.3, grW) + stroke(gr - outer, 0.3, grW);
        // Radial filaments between the rings, faint.
        float spokes = stroke(sin((ga.x + turned * 0.2) * 24.0) * gr, 0.25, grW * 4.0) * step(0.16 * R, gr) * step(gr, 0.3 * R);
        light += spokes * 0.5;
        float inside = ColumnRadius > 0.0 ? 1.0 - smoothstep(ColumnRadius - 2.0, ColumnRadius + 2.0, gr) : 0.0;
        vec3 colour = SEAL_BLUE * light * (1.0 + 1.6 * inside) + vec3(1.0, 0.72, 0.3) * gold * 1.4 + vec3(0.9) * light * inside * 0.6;
        float edge = 1.0 - smoothstep(R * 0.98, R * 1.02, gr);
        addLayer(sealT, colour * SealGlow * edge * 0.9, 0.0);
    }

    // --- The column of light: a thin glowing wall, brightest where it is seen edge-on so it reads as a cylinder, runes
    // rising up it in columns, helical bands of turquoise and gold, fading as it goes up into the sky. ---
    if (ColumnRadius > 0.05 && ColumnGlow > 0.001 && columnT > 0.05 && columnT < scene && cw.y > 0.0 && cw.y < ColumnTop) {
        vec3 normal = normalize(cp - Up * dot(cp, Up));
        float rim = pow(1.0 - abs(dot(normal, dir)), 3.0);
        float band = smoothstep(0.25, 0.75, 0.5 + 0.5 * sin(cA.x * 3.0 + cw.y * 0.02 - Time * 0.03));
        vec3 colour = mix(TURQUOISE, vec3(1.0, 0.68, 0.22), band);
        vec2 q = vec2(fract(cg.x), 1.0 - fract(cg.y));
        float cellPick = hash1(floor(cg) + vec2(3.0, 11.0));
        float kind = floor(hash1(floor(cg) + vec2(7.0, 1.0)) * 28.0);
        float rune = cellPick < 0.45 ? glyph(kind, (q - 0.5) / 0.8 + 0.5, cgdx / 0.8 * vec2(1.0, -1.0), cgdy / 0.8 * vec2(1.0, -1.0)) : 0.0;
        float twinkle = 0.6 + 0.4 * sin(Time * 0.15 + cellPick * 40.0);
        float streak = smoothstep(0.75, 1.0, noise(vec3(cA.x * 20.0, cw.y * 0.01 - Time * 0.05, 1.0)));
        float height = exp(-cw.y / 420.0) * (1.0 - smoothstep(ColumnTop * 0.8, ColumnTop, cw.y));
        float runes = smoothstep(3.0, 10.0, ColumnRadius);
        vec3 light = colour * (0.015 + 0.8 * rim + 0.25 * streak) + mix(colour, vec3(1.0), 0.3) * rune * runes * twinkle * 1.1;
        float fade = far ? 0.55 : 1.0;
        // The wall veils a little of what lies behind it, so the sky's blue does not wash out its gold.
        addLayer(columnT, light * ColumnGlow * height * fade, (0.06 + 0.2 * rim) * ColumnGlow * height);
    }

    // --- The crescent moon high over the blast, pale against the white sky. ---
    if (!far && MoonGlow > 0.001 && MoonRadius > 0.0) {
        float mb = dot(MoonAt, dir), mc2 = dot(MoonAt, MoonAt) - mb * mb, mr2 = MoonRadius * MoonRadius;
        float mmu = mc2 < mr2 ? sqrt(1.0 - mc2 / mr2) : 0.0, msurface = mb - MoonRadius * mmu;
        if (mb > 0.0 && mc2 < mr2 && scene > msurface) {
            vec3 p = dir * msurface - MoonAt;
            vec3 n = p / MoonRadius;
            float lit = smoothstep(-0.04, 0.14, dot(n, MoonLight));
            vec3 w = vec3(dot(n, East), dot(n, Up), dot(n, North));
            float craters = 0.82 + 0.18 * noise(w * 6.0) - 0.12 * smoothstep(0.55, 0.8, noise(w * 13.0 + 3.0));
            // Pale against the white: a little grey and lilac, the white showing through.
            vec3 colour = vec3(0.72, 0.7, 0.8) * craters;
            addLayer(msurface, colour * lit * MoonGlow * 0.6, lit * MoonGlow * 0.6);
        }
    }

    // Nearest first: each layer shows over what lies behind it.
    for (int i = 1; i < MAX_LAYERS; i++) {
        if (i >= layers) {
            break;
        }
        for (int j = i; j > 0; j--) {
            if (layerAt[j - 1] <= layerAt[j]) {
                break;
            }
            float at = layerAt[j];
            vec4 colour = layerColour[j];
            layerAt[j] = layerAt[j - 1];
            layerColour[j] = layerColour[j - 1];
            layerAt[j - 1] = at;
            layerColour[j - 1] = colour;
        }
    }
    vec3 light = vec3(0.0);
    float through = 1.0;
    for (int i = 0; i < MAX_LAYERS; i++) {
        if (i >= layers) {
            break;
        }
        light += through * layerColour[i].rgb;
        through *= 1.0 - layerColour[i].a;
    }
    // Bright light burns towards white rather than clipping flat.
    fragColor = vec4(vec3(1.0) - exp(-light * 1.5), 1.0 - through);
}
