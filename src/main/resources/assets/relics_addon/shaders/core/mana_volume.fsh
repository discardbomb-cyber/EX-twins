#version 150

// What Mana Armageddon fills the air with, ray-marched through the world behind it at a fraction of the screen's
// size and laid between the far and near surfaces of mana_shell: the dark, writhing vortex where the streams collide;
// the glowing turquoise-gold fog inside the sphere of runes, densest round the sun and lit by it, swirling slowly and
// thinning to nothing at the shell; the thin horizontal flash as the sphere shatters; the ring of dust running out
// along the ground round a dark core; the dome of light sweeping out over the land; the haze in the column of light,
// swirling and rising, brightest at its axis; a light haze over the seal on the ground; and the white air the column
// dissolves into. Each volume is marched only over the stretch of the ray that crosses it.
uniform sampler2D Sampler1;   // depth of the scene
uniform mat4 InverseProj;
uniform vec2 TargetSize;      // the size of the (reduced) target this pass draws into
uniform vec3 Centre;          // where the streams met, on the ground, in view space
uniform vec3 Up;              // the world's up, in view space
uniform vec3 East;            // the world's east (+x), in view space
uniform vec3 North;           // the world's south (+z), in view space
uniform vec3 Seam;            // level, from the centre towards where the shot came from, in view space
uniform float Time;           // ticks since the burst (negative before it)
uniform float Reach;          // how far out the air is touched at all
uniform float Vortex;         // how thick the vortex is, 0..1
uniform float SphereRadius;
uniform float SphereFog;
uniform float SunRadius;
uniform float SunLift;
uniform float SunGlow;
uniform float Flash;          // the thin flash, 0..1
uniform float ShockRadius;    // how far the ring of dust has run
uniform float Shock;          // how thick it is
uniform float DarkCore;       // the dark core in the ring's middle
uniform float Front;          // the dome of light's radius
uniform float Wave;           // how thick the haze of its front is
uniform float Glare;          // how thick its light hangs inside it
uniform float ColumnRadius;
uniform float ColumnFog;
uniform float ColumnTop;
uniform float SealRadius;
uniform float GroundHaze;
uniform float White;          // the white air after the column dissolves, 0..1

in vec3 viewPos;

out vec4 fragColor;

const vec3 TURQUOISE = vec3(0.31, 0.95, 0.85);
const vec3 GOLD = vec3(1.0, 0.8, 0.42);

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

// A world-axes point turned about the vertical by {@code angle}.
vec3 turned(vec3 w, float angle) {
    float c = cos(angle), s = sin(angle);
    return vec3(c * w.x - s * w.z, w.y, s * w.x + c * w.z);
}

// Where the ray [near, far] crosses the level band of heights [low, high] over the centre.
vec2 heights(vec3 dir, float near, float far, float low, float high) {
    float along = dot(dir, Up), base = dot(-Centre, Up);
    if (abs(along) < 1.0e-5) {
        return base >= low && base <= high ? vec2(near, far) : vec2(1.0, 0.0);
    }
    float t0 = (low - base) / along, t1 = (high - base) / along;
    return vec2(max(near, min(t0, t1)), min(far, max(t0, t1)));
}

// Where the ray crosses the vertical cylinder of {@code radius} round the centre's axis.
vec2 cylinder(vec3 dir, float radius, float near, float far) {
    vec3 dh = dir - Up * dot(dir, Up), ch = -Centre - Up * dot(-Centre, Up);
    float a = dot(dh, dh), b = dot(dh, ch), c = dot(ch, ch) - radius * radius, d = b * b - a * c;
    if (a < 1.0e-8) {
        return c < 0.0 ? vec2(near, far) : vec2(1.0, 0.0);
    }
    if (d < 0.0) {
        return vec2(1.0, 0.0);
    }
    d = sqrt(d);
    return vec2(max(near, (-b - d) / a), min(far, (-b + d) / a));
}

// Where the ray crosses the ball of {@code radius} round {@code centre}.
vec2 ball(vec3 dir, vec3 centre, float radius, float near, float far) {
    float b = dot(centre, dir), c = dot(centre, centre) - radius * radius, h = b * b - c;
    if (h < 0.0) {
        return vec2(1.0, 0.0);
    }
    h = sqrt(h);
    return vec2(max(near, b - h), min(far, b + h));
}

void main() {
    vec2 uv = gl_FragCoord.xy / TargetSize;
    vec3 dir = normalize(viewPos);
    float scene = sceneDistance(uv);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 seamRight = normalize(cross(Seam, Up));
    vec3 light = vec3(0.0);
    float through = 1.0;

    // --- The vortex where the streams collide: dark smoke writhing up round an axis that wanders, lit turquoise and
    // gold at its edges. ---
    if (Vortex > 0.001) {
        vec2 span = cylinder(dir, 16.0, 0.05, scene);
        span = heights(dir, span.x, span.y, -2.0, 56.0);
        if (span.y > span.x) {
            const int STEPS = 18;
            float step_ = (span.y - span.x) / float(STEPS);
            for (int i = 0; i < STEPS; i++) {
                vec3 p = dir * (span.x + (float(i) + jitter) * step_) - Centre;
                vec3 w = vec3(dot(p, East), dot(p, Up), dot(p, North));
                vec2 axis = vec2(cos(w.y * 0.09 + Time * 0.07), sin(w.y * 0.09 + Time * 0.07)) * (2.0 + w.y * 0.08);
                float radius = 4.5 + w.y * 0.1, away = length(w.xz - axis);
                float n = fbm(turned(w, -Time * 0.05 - w.y * 0.04) * 0.14 + vec3(0.0, -Time * 0.05, 0.0));
                float smoke = (1.0 - smoothstep(radius * 0.4, radius * (1.0 + 0.5 * n), away)) * smoothstep(-1.0, 3.0, w.y) * (1.0 - smoothstep(40.0, 56.0, w.y));
                float edge = smoke * (1.0 - smoke) * 4.0;
                vec3 glow = mix(TURQUOISE, GOLD, 0.5 + 0.5 * sin(w.y * 0.2 + Time * 0.1)) * edge * 0.05;
                float absorb = smoke * 0.14 * (0.5 + n);
                light += through * glow * Vortex * step_;
                through *= exp(-absorb * Vortex * step_);
            }
        }
    }

    // --- The fog inside the sphere of runes: densest round the sun, thinning to nothing at the shell, swirling slowly,
    // lit by the sun; turquoise on the seam's left and gold on its right. Nothing is seen through the sun. ---
    if (SphereFog > 0.001 && SphereRadius > 0.1) {
        vec2 span = ball(dir, Centre, SphereRadius, 0.05, scene);
        span = heights(dir, span.x, span.y, 0.0, SphereRadius);
        vec3 sun = Centre + Up * SunLift;
        vec2 sunSpan = ball(dir, sun, SunRadius, 0.05, 1.0e6);
        if (sunSpan.y > sunSpan.x) {
            span.y = min(span.y, sunSpan.x);
        }
        if (span.y > span.x) {
            const int STEPS = 22;
            float step_ = (span.y - span.x) / float(STEPS);
            for (int i = 0; i < STEPS; i++) {
                vec3 p = dir * (span.x + (float(i) + jitter) * step_) - Centre;
                vec3 w = vec3(dot(p, East), dot(p, Up), dot(p, North));
                float r = length(w) / SphereRadius;
                vec3 fromSun = p - Up * SunLift;
                float toSun = length(fromSun);
                float n = fbm(turned(w, Time * 0.02 + r * 1.5) * (3.0 / SphereRadius) + vec3(0.0, Time * 0.012, 0.0));
                float dense = (0.3 * (1.0 - r * r) + 1.1 * exp(-toSun / (SphereRadius * 0.28))) * (0.55 + 0.9 * n) * (1.0 - smoothstep(0.85, 1.0, r));
                float lit = SunGlow * 2.6 / (1.0 + toSun * toSun / (SunRadius * SunRadius * 4.0 + 1.0));
                vec3 colour = mix(TURQUOISE, GOLD, smoothstep(-0.3, 0.3, dot(p, seamRight) / SphereRadius));
                colour = mix(colour, vec3(1.0, 0.96, 0.85), clamp(lit * 0.3, 0.0, 0.7));
                light += through * colour * dense * (0.03 + lit * 0.045) * SphereFog * step_;
                through *= exp(-dense * 0.035 * SphereFog * step_);
            }
        }
    }

    // --- The haze in the column of light: swirling and rising, denser and brighter towards its axis, softer at its
    // wall, fading as it goes up into the sky. ---
    if (ColumnFog > 0.001 && ColumnRadius > 0.1) {
        vec2 span = cylinder(dir, ColumnRadius, 0.05, scene);
        span = heights(dir, span.x, span.y, 0.0, ColumnTop);
        if (span.y > span.x) {
            const int STEPS = 24;
            float length_ = span.y - span.x, step_ = length_ / float(STEPS);
            for (int i = 0; i < STEPS; i++) {
                vec3 p = dir * (span.x + (float(i) + jitter) * step_) - Centre;
                vec3 w = vec3(dot(p, East), dot(p, Up), dot(p, North));
                float r = length(w.xz) / ColumnRadius;
                float n = fbm(turned(w, Time * 0.012 + w.y * 0.006) * (2.4 / max(ColumnRadius, 6.0)) + vec3(0.0, -Time * 0.03, 0.0));
                float dense = (0.22 + 0.9 * pow(max(1.0 - r, 0.0), 1.6)) * (0.5 + 0.9 * n) * exp(-w.y / 420.0);
                float around = atan(w.z, w.x);
                vec3 colour = mix(TURQUOISE, vec3(1.0, 0.7, 0.28), smoothstep(0.2, 0.8, 0.5 + 0.5 * sin(around * 2.0 + w.y * 0.015 - Time * 0.02)));
                colour = mix(colour, vec3(1.0, 0.98, 0.92), pow(max(1.0 - r, 0.0), 3.0) * 0.7);
                light += through * colour * dense * 0.011 * ColumnFog * step_;
                through *= exp(-dense * 0.006 * ColumnFog * step_);
            }
        }
    }

    // --- The air round the blast: the flash, the ring of dust and its dark core, the dome of light, the haze over the
    // seal, and the white air; marched from the eye out, the steps crowding close to it. ---
    if (Flash + Shock + DarkCore + Wave + Glare + GroundHaze + White > 0.001) {
        float b = dot(Centre, dir), c = dot(Centre, Centre) - Reach * Reach, h = b * b - c;
        if (h > 0.0) {
            h = sqrt(h);
            float near = max(b - h, 0.05), far = min(b + h, scene);
            if (far > near) {
                const int STEPS = 32;
                float span = far - near;
                for (int i = 0; i < STEPS; i++) {
                    float u0 = (float(i) + jitter) / float(STEPS), u1 = (float(i) + 1.0 + jitter) / float(STEPS);
                    float t = near + span * u0 * u0, step_ = span * (u1 * u1 - u0 * u0);
                    vec3 p = dir * t - Centre;
                    vec3 w = vec3(dot(p, East), dot(p, Up), dot(p, North));
                    float out_ = length(w.xz), r = length(w);
                    vec3 glow = vec3(0.0);
                    float absorb = 0.0;
                    // The flash: a thin sheet of light across the land, streaked, blinding.
                    if (Flash > 0.001) {
                        float around = atan(w.z, w.x);
                        float streak = 0.6 + 0.8 * smoothstep(0.55, 0.95, noise(vec3(around * 12.0, 0.0, 3.0)));
                        float sheet = Flash * exp(-abs(w.y - 2.5) / 0.7) * exp(-out_ / 90.0) * streak;
                        glow += vec3(1.0, 0.97, 0.88) * sheet * 0.35;
                        absorb += sheet * 0.05;
                    }
                    // The ring of dust running out along the ground, and the dark core it leaves.
                    if (Shock > 0.001 && abs(out_ - ShockRadius) < 30.0 && w.y < 26.0) {
                        float thick = 5.0 + ShockRadius * 0.05, tall = 3.0 + 10.0 * (1.0 - ShockRadius / 170.0);
                        float n = fbm(w * 0.08 + vec3(Time * 0.05, 0.0, -Time * 0.03));
                        float dust = Shock * exp(-pow((out_ - ShockRadius) / thick, 2.0)) * exp(-max(w.y, 0.0) / tall) * (0.4 + 1.1 * n);
                        glow += mix(vec3(0.95, 0.82, 0.6), vec3(1.0, 0.97, 0.9), smoothstep(0.0, thick, out_ - ShockRadius + thick * 0.5)) * dust * 0.06;
                        absorb += dust * 0.045;
                    }
                    if (DarkCore > 0.001 && r < 18.0) {
                        float n = fbm(w * 0.2 + vec3(0.0, Time * 0.04, 0.0));
                        float dark = DarkCore * (1.0 - smoothstep(6.0, 11.0 + 3.0 * n, length(w - vec3(0.0, 5.0, 0.0))));
                        glow += vec3(0.05, 0.2, 0.2) * dark * 0.01;
                        absorb += dark * 0.35;
                    }
                    // The dome of light: a wave of glowing haze, its front ragged and billowing, its wake glowing, its
                    // light filling the air inside.
                    if (Wave > 0.001 && Front > 1.0 && w.y > -3.0) {
                        float n = fbm(w * 0.026 + vec3(0.0, -Time * 0.05, Time * 0.03));
                        float s = Front - (r + (n - 0.5) * 26.0);
                        if (s > -8.0 && s < 70.0) {
                            float surf = smoothstep(-8.0, 0.0, s) * (1.0 - smoothstep(0.0, 12.0, s));
                            float wake = smoothstep(0.0, 8.0, s) * exp(-s / 24.0);
                            float scour = 1.0 + 2.0 * exp(-max(w.y, 0.0) / 9.0);
                            float billow = 0.45 + 1.1 * smoothstep(0.35, 0.8, fbm(w * 0.075 + vec3(Time * 0.05, -Time * 0.03, 0.0)));
                            float haze = (surf * 0.06 + wake * 0.014) * scour * billow * Wave;
                            glow += mix(TURQUOISE * 0.8, vec3(1.0, 0.95, 0.82), surf) * haze * 2.4;
                            absorb += haze * 0.7;
                        }
                    }
                    if (Glare > 0.001 && r < Front && w.y > -2.0) {
                        float air = 0.0035 * (0.8 + 0.4 * fbm(w * 0.02 + vec3(Time * 0.01)));
                        glow += vec3(1.0, 0.95, 0.84) * air * 1.5 * Glare;
                        absorb += air * Glare;
                    }
                    // A light haze lying over the seal.
                    if (GroundHaze > 0.001 && out_ < SealRadius && w.y > -1.0 && w.y < 8.0) {
                        float n = fbm(w * 0.12 + vec3(Time * 0.02, 0.0, Time * 0.015));
                        float haze = GroundHaze * exp(-max(w.y, 0.0) / 1.6) * (0.4 + 0.8 * n) * (1.0 - smoothstep(SealRadius * 0.8, SealRadius, out_));
                        glow += vec3(0.62, 0.86, 1.0) * haze * 0.05;
                        absorb += haze * 0.01;
                    }
                    // The white air the column dissolves into.
                    if (White > 0.001) {
                        float air = White * 0.012;
                        glow += vec3(1.0, 0.99, 0.97) * air * 1.3;
                        absorb += air;
                    }
                    light += through * glow * step_;
                    through *= exp(-absorb * step_);
                    if (through < 0.01) {
                        break;
                    }
                }
            }
        }
    }
    fragColor = vec4(vec3(1.0) - exp(-light * 1.4), through);
}
