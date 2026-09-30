#version 150

// What the Armageddon supernova fills the air with, ray-marched through the world behind it: the flash's white
// light; the ball of light, a wave of glowing haze swelling out over the land with a ragged, billowing, splashing
// front and a glowing wake, thickest where it scours the ground, the light inside it swallowing everything far
// off so that what is near stands out dark against it; the violet haze of the eruption; and the light boiling up
// round the beam's foot, with black smoke writhing up inside the beam and balls of it rising at each deep pop.
// Each is soft, so it is marched at a fraction of the screen's size and laid over the
// world after; whatever of the world stands in front of it hides it.
uniform sampler2D Sampler1;   // depth of the scene
uniform mat4 InverseProj;
uniform vec2 TargetSize;      // the size of the (reduced) target this pass draws into
uniform vec3 Centre;          // where the blast burst, on the ground, in view space
uniform vec3 Up;              // the world's up, in view space
uniform vec3 East;            // the world's east (+x), in view space
uniform vec3 North;           // the world's south (+z), in view space
uniform float Time;           // ticks since the burst
uniform float Front;          // the dome of light's radius
uniform float Flash;          // how thick the white light of the flash hangs in the air, 0..1
uniform float Glare;          // how thick its light hangs in the air, 0..1
uniform float Wave;           // how thick the haze of its front is, 0..1
uniform float Magenta;        // how thick the magenta haze is
uniform float Pillar;         // the pillar of light's radius
uniform float PillarGlow;     // how bright it is
uniform float Dust;           // the orange dust round the ring
uniform float Core;           // the radius of the column of black smoke inside the beam
uniform float Spheres;        // how many deep pops of the eruption have come (a ball of black smoke at each), the fraction the time since the latest
uniform float Reach;          // how far out any of it goes

in vec3 viewPos;

out vec4 fragColor;

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
    float b = dot(Centre, dir), c = dot(Centre, Centre) - Reach * Reach, h = b * b - c;
    if (h < 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    h = sqrt(h);
    float near = max(b - h, 0.05), far = min(b + h, sceneDistance(uv));
    if (far <= near) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 east = East, north = North;

    // Steps crowd close to the eye; each pixel starts them a little further on than its neighbours, in a
    // pattern even enough for the blur that lays this over the world.
    // The balls of black smoke alive now (each lives three pops), worked out once for the whole ray.
    vec4 balls[4];
    int ballCount = 0;
    if (PillarGlow > 0.001) {
        for (int k = 0; k < 48; k++) {
            float fk = float(k), age = (Spheres - fk) / 3.0;
            if (age < 0.0) {
                break;
            }
            if (age > 1.0 || ballCount >= 4) {
                continue;
            }
            float angle = fract(sin((fk + 3.0) * 91.3458) * 47453.5453) * 6.2831853;
            float out_ = Pillar * (0.3 + 0.4 * fract(sin((fk + 5.0) * 91.3458) * 47453.5453));
            float height = 20.0 + 3.0 * Pillar * fract(sin((fk + 7.0) * 91.3458) * 47453.5453) + 2.5 * Pillar * age;
            float radius = Pillar * (0.12 + 0.12 * fract(sin((fk + 9.0) * 91.3458) * 47453.5453)) * (age < 0.1 ? age / 0.1 : 1.0 - smoothstep(0.6, 1.0, age));
            balls[ballCount] = vec4(cos(angle) * out_, height, sin(angle) * out_, radius);
            ballCount++;
        }
    }

    const int STEPS = 36;
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 light = vec3(0.0);
    float through = 1.0, span = far - near;
    for (int i = 0; i < STEPS; i++) {
        float u0 = (float(i) + jitter) / float(STEPS), u1 = (float(i) + 1.0 + jitter) / float(STEPS);
        float t = near + span * u0 * u0, step_ = span * (u1 * u1 - u0 * u0);
        vec3 p = dir * t - Centre;
        float up = dot(p, Up), out_ = length(p - Up * up), r = length(p);
        vec3 w = vec3(dot(p, east), up, dot(p, north));
        float absorb = 0.0;
        vec3 glow = vec3(0.0);

        // The flash: the air itself burning cream-white, so that everything near stands dark against it.
        if (Flash > 0.001) {
            float air = Flash * (0.012 + 0.2 * smoothstep(0.5, 1.0, Flash));
            glow += vec3(1.0, 0.96, 0.9) * air * 1.25;
            absorb += air;
        }

        // The ball of light: a wave of glowing haze, its front ragged and billowing as it splashes out, a glowing
        // wake behind it, thickest where it scours the ground; and its light filling the air inside.
        if (Wave > 0.001 && Front > 1.0 && up > -3.0) {
            float n = fbm(w * 0.026 + vec3(0.0, -Time * 0.05, Time * 0.03));
            float s = Front - (r + (n - 0.5) * 26.0);
            if (s > -8.0 && s < 70.0) {
                float surf = smoothstep(-8.0, 0.0, s) * (1.0 - smoothstep(0.0, 12.0, s));
                float wake = smoothstep(0.0, 8.0, s) * exp(-s / 24.0);
                float scour = 1.0 + 2.0 * exp(-max(up, 0.0) / 9.0);
                float billow = 0.45 + 1.1 * smoothstep(0.35, 0.8, fbm(w * 0.075 + vec3(Time * 0.05, -Time * 0.03, 0.0)));
                float haze = (surf * 0.06 + wake * 0.014) * scour * billow * Wave;
                glow += mix(vec3(0.8, 0.45, 1.0), vec3(1.0, 0.96, 1.0), surf) * haze * 2.4;
                absorb += haze * 0.8;
            }
        }
        if (Glare > 0.001 && r < Front && up > -2.0) {
            float air = 0.009 * (0.8 + 0.4 * fbm(w * 0.02 + vec3(Time * 0.01)));
            glow += vec3(0.97, 0.9, 1.0) * air * 1.6 * Glare;
            absorb += air * Glare;
        }
        // The magenta haze lying over the land after.
        if (Magenta > 0.001 && up < 45.0 && out_ < Reach) {
            float n = fbm(vec3(dot(p, east), up * 2.0, dot(p, north)) * 0.03 + vec3(Time * 0.004));
            // Thinning away well before its reach, so it has no edge to be seen.
            float haze = (0.4 + 0.6 * n) * exp(-max(up + 1.0, 0.0) / 18.0) * (1.0 - smoothstep(Reach * 0.35, Reach * 0.9, out_));
            glow += vec3(1.0, 0.22, 0.8) * haze * 0.006 * Magenta;
            absorb += haze * 0.003 * Magenta;
        }
        // The beam: energy streaming up to the zenith in a tube of glowing haze, brightest in its walls, streaked.
        if (PillarGlow > 0.001 && out_ < Pillar * 1.35 && up > -3.0) {
            float n = fbm(vec3(w.x * 0.05, w.y * 0.012 - Time * 0.6, w.z * 0.05));
            float edge = out_ + (n - 0.5) * Pillar * 0.3;
            float inside = 1.0 - smoothstep(Pillar * 0.9, Pillar * 1.15, edge);
            float wall = smoothstep(Pillar * 0.45, Pillar * 0.95, edge) * inside;
            float around = atan(w.z, w.x);
            float streak = smoothstep(0.62, 0.9, noise(vec3(around * 7.0, w.y * 0.004 - Time * 0.09, out_ * 0.08)));
            float fade = exp(-max(up, 0.0) / 500.0);
            float energy = (inside * 0.004 + wall * 0.03 + streak * inside * 0.02) * fade;
            glow += mix(vec3(0.72, 0.38, 1.0), vec3(1.0, 0.96, 1.0), wall * 0.7 + streak * 0.3) * energy * 2.2 * PillarGlow;
            absorb += energy * 0.25 * PillarGlow;
        }
        // The violet glow round the beam, and the light boiling up round its foot.
        if (PillarGlow > 0.001) {
            // Only round the beam, never inside it, so its black pillar stays black.
            float glowing = out_ > Pillar ? exp(-(out_ - Pillar) / (Pillar * 0.6 + 6.0)) * exp(-max(up, 0.0) / 220.0) : 0.0;
            glow += vec3(0.7, 0.35, 1.0) * glowing * 0.004 * PillarGlow;
            float foot = out_ > Pillar * 0.8 ? exp(-(out_ - Pillar * 0.8) / (Pillar * 0.7 + 4.0)) * exp(-max(up, 0.0) / (Pillar * 0.5 + 8.0)) : 0.0;
            if (foot > 0.01) {
                float n = fbm(vec3(dot(p, east), up * 0.5 - Time * 0.35, dot(p, north)) * 0.09);
                float plume = foot * smoothstep(0.38, 0.78, n);
                glow += mix(vec3(0.7, 0.35, 1.0), vec3(1.0, 0.95, 1.0), n) * plume * 0.09 * PillarGlow;
                absorb += plume * 0.025 * PillarGlow;
            }
        }
        // Black smoke writhing up inside the beam round an axis that wanders, and balls of it rising at each pop.
        if (PillarGlow > 0.001 && Core > 0.05 && out_ < Pillar * 1.1) {
            vec2 wander = vec2(cos(Time * 0.11), sin(Time * 0.11)) * Pillar * 0.18;
            float n = fbm(vec3(w.x * 0.06, w.y * 0.025 - Time * 0.22, w.z * 0.06));
            float away = length(w.xz - wander) + (n - 0.5) * Core * 0.9;
            float smoke = (1.0 - smoothstep(Core * 0.55, Core * 1.1, away)) * smoothstep(-2.0, 6.0, up);
            for (int k = 0; k < 4; k++) {
                if (k >= ballCount) {
                    break;
                }
                float d = length(w - balls[k].xyz) + (n - 0.5) * balls[k].w * 0.7;
                smoke = max(smoke, 1.0 - smoothstep(balls[k].w * 0.45, balls[k].w * 1.05, d));
            }
            absorb += smoke * 0.09 * PillarGlow;
            glow += vec3(0.28, 0.1, 0.42) * smoke * 0.004 * PillarGlow;
        }
        // Orange dust round the ring in the silence.
        if (Dust > 0.001 && out_ < 60.0 && up < 12.0) {
            float n = fbm(w * 0.12 + vec3(0.0, -Time * 0.02, 0.0));
            float dust = smoothstep(0.55, 0.7, n) * exp(-max(up, 0.0) / 4.0) * (1.0 - smoothstep(20.0, 60.0, out_));
            glow += vec3(1.0, 0.55, 0.3) * dust * 0.04 * Dust;
            absorb += dust * 0.03 * Dust;
        }

        light += through * glow * step_;
        through *= exp(-absorb * step_);
        if (through < 0.01) {
            break;
        }
    }
    fragColor = vec4(vec3(1.0) - exp(-light * 1.4), through);
}
