#version 150

// The Armageddon shot's light, the one solid shape left of it (the supernova itself, its ball of light, the beam
// of energy and the black smoke inside it, is all haze, marched in armageddon_volume): a ball of white light
// flying to its target, and the pulsar beating at the point the black hole collapses into. Surfaces are found exactly rather than marched, so their
// edges stay sharp; everything keeps to the world's own axes, so nothing turns as the camera turns; the world's
// depth hides whatever stands behind the world. Drawn with premultiplied alpha: light added, what lies behind
// dimmed by what covers it.
uniform sampler2D Sampler1;   // depth of the scene
uniform mat4 InverseProj;
uniform vec2 TargetSize;
uniform vec3 Centre;          // where the blast burst, on the ground, in view space
uniform vec3 Up;              // the world's up, in view space
uniform vec3 East;            // the world's east (+x), in view space
uniform vec3 North;           // the world's south (+z), in view space
uniform float Time;           // ticks since the burst
uniform float PixelAngle;     // how wide a pixel is a block away, so thin lines stay a pixel or two wide
uniform vec3 Orb;             // the shot: where it is, in view space
uniform float OrbRadius;      // the radius of its shell of light
uniform float OrbGlow;        // how bright it is
uniform float OrbTime;        // the shot's own clock, ticks since it started
uniform float OrbSolid;       // 1 for a solid ball of light, 0 for a shell that shows what is inside it

in vec3 viewPos;

out vec4 fragColor;

const int MAX_LAYERS = 12;
float layerAt[MAX_LAYERS];
vec4 layerColour[MAX_LAYERS];
int layers = 0;

// A layer of what the ray meets, {@code at} blocks along it: light it gives, and how much of what lies behind it hides.
void addLayer(float at, vec3 light, float cover) {
    if (layers < MAX_LAYERS) {
        layerAt[layers] = at;
        layerColour[layers] = vec4(light, clamp(cover, 0.0, 1.0));
        layers++;
    }
}

float hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1.0, 0.0, 0.0)), f.x),
                   mix(hash(i + vec3(0.0, 1.0, 0.0)), hash(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
               mix(mix(hash(i + vec3(0.0, 0.0, 1.0)), hash(i + vec3(1.0, 0.0, 1.0)), f.x),
                   mix(hash(i + vec3(0.0, 1.0, 1.0)), hash(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float sum = 0.0, amplitude = 0.5;
    for (int octave = 0; octave < 3; octave++) {
        sum += amplitude * noise(p);
        p = p * 2.07 + vec3(1.7, 9.2, 4.1);
        amplitude *= 0.5;
    }
    return sum / 0.875;
}

float sceneDistance(vec2 uv) {
    float depth = texture(Sampler1, uv).r;
    if (depth >= 1.0) {
        return 1.0e6;
    }
    vec4 point = InverseProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return length(point.xyz / point.w);
}

void main() {
    vec2 uv = gl_FragCoord.xy / TargetSize;
    vec3 dir = normalize(viewPos);
    float scene = sceneDistance(uv);
    vec3 east = East, north = North;

    // --- The shot: a shell of violet-white light round the black hole, bands of it streaming round, brightest at its rim. ---
    if (OrbGlow > 0.001 && OrbRadius > 0.05) {
        float b = dot(Orb, dir), closest = sqrt(max(dot(Orb, Orb) - b * b, 0.0));
        if (b > 0.0 && b - OrbRadius < scene) {
            float halo = exp(-max(closest - OrbRadius, 0.0) / (OrbRadius * 0.6));
            float shell = closest < OrbRadius ? mix(pow(closest / OrbRadius, 3.0), 1.0, OrbSolid) : 0.0;
            vec3 light = vec3(0.75, 0.38, 1.0) * halo * 0.9 + vec3(1.0, 0.92, 1.0) * shell * 1.6;
            if (closest < OrbRadius) {
                vec3 p = dir * (b - sqrt(OrbRadius * OrbRadius - closest * closest)) - Orb;
                vec3 w = vec3(dot(p, east), dot(p, Up), dot(p, north)) / OrbRadius;
                float around = atan(w.z, w.x);
                float band = sin(around * 3.0 + w.y * 5.0 - OrbTime * 0.45) * 0.5 + 0.5;
                band = smoothstep(0.55, 0.9, band + (noise(w * 2.5 + vec3(0.0, -OrbTime * 0.05, 0.0)) - 0.5) * 0.5);
                light += mix(vec3(0.7, 0.35, 1.0), vec3(1.0), band) * band * (0.5 + shell) * 1.2;
            }
            addLayer(b, light * OrbGlow, 0.0);
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
