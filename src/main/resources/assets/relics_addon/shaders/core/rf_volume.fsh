#version 150

// RF Armageddon's ball and dome, laid over the finished world with premultiplied alpha. The ball: a near-black navy
// core, slowly swirling, in a bright electric rim and a halo round it. The dome it becomes: a hemisphere of ice-blue
// glass on the ground, bright at its edges and faintly banded, hazed inside as thick as the glass is deep, heating to
// white; and where its edge cuts the ground, a hot ring on the land. Both keep behind whatever of the world stands in
// front of them.
uniform sampler2D Sampler1;   // depth of the scene
uniform mat4 InverseProj;
uniform vec2 TargetSize;
uniform vec3 Centre;          // the dome's middle on the ground, in view space
uniform vec3 Up;              // the world's up, in view space
uniform vec3 BallCentre;      // in view space
uniform float BallRadius;
uniform float BallCore;       // how opaque the dark core is
uniform float BallRim;        // how bright its rim burns
uniform float Time;           // ticks, for the core's slow swirl and the glass's bands
uniform float DomeRadius;
uniform float DomeGlass;      // how much of the dome is there
uniform float DomeHeat;       // 0 ice-blue glass, 1 white-hot
uniform float DomeHaze;       // how thick the haze inside is
uniform float DomeEdge;       // how bright its edge burns where it cuts the ground

in vec3 viewPos;

out vec4 fragColor;

// Where a ray from the eye along dir enters and leaves a sphere; x > y when it misses.
vec2 sphere(vec3 dir, vec3 centre, float radius) {
    float b = dot(centre, dir);
    float c = dot(centre, centre) - radius * radius;
    float h = b * b - c;
    if (h < 0.0) return vec2(1e9, -1e9);
    h = sqrt(h);
    return vec2(b - h, b + h);
}

float hash(vec3 p) {
    return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
}

float noise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x), mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y);
    float b = mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x), mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y);
    return mix(a, b, f.z);
}

void main() {
    vec2 uv = gl_FragCoord.xy / TargetSize;
    float depth = texture(Sampler1, uv).r;
    vec3 dir = normalize(viewPos);
    float scene = 1e9;
    vec3 point = vec3(0.0);
    if (depth < 1.0) {
        vec4 p = InverseProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        point = p.xyz / p.w;
        scene = length(point);
    }

    vec3 colour = vec3(0.0);
    float alpha = 0.0;

    // The dome of glass.
    if (DomeGlass > 0.001 && DomeRadius > 0.5) {
        vec3 tint = mix(vec3(0.5, 0.84, 1.0), vec3(1.0, 0.97, 0.92), DomeHeat);
        float glowing = 0.35 + 2.2 * DomeHeat;
        vec2 hit = sphere(dir, Centre, DomeRadius);
        // Only the half above the ground: the ray's height over it is t * rise - floorAt.
        float rise = dot(dir, Up), floorAt = dot(Centre, Up);
        float lo = max(hit.x, 0.0), hi = min(hit.y, scene);
        if (abs(rise) > 1e-5) {
            float plane = floorAt / rise;
            if (rise > 0.0) lo = max(lo, plane);
            else hi = min(hi, plane);
        } else if (floorAt > 0.0) {
            hi = lo - 1.0;
        }
        if (hit.x <= hit.y && hi > lo) {
            float haze = 1.0 - exp(-DomeHaze * (hi - lo) / DomeRadius);
            float skinA = 0.0;
            vec3 skin = vec3(0.0);
            // Seen through its skin (not from inside it, nor through the ground): bright where the glass is seen edge on.
            if (hit.x > 0.0 && lo <= hit.x + 1e-3 * max(1.0, hit.x)) {
                vec3 at = dir * hit.x - Centre;
                vec3 n = at / DomeRadius;
                float edgeOn = pow(1.0 - abs(dot(n, -dir)), 3.0);
                float bands = smoothstep(0.9, 1.0, cos(dot(at, Up) / DomeRadius * 40.0 + Time * 0.08)) * (0.4 - 0.3 * DomeHeat);
                skinA = 0.1 + 0.75 * edgeOn + 0.25 * bands;
                skin = tint * (0.3 + 1.6 * edgeOn + bands) * glowing;
            }
            alpha = clamp(DomeGlass * (haze * (0.3 + 0.55 * DomeHeat) + skinA), 0.0, 0.97);
            colour = DomeGlass * (tint * glowing * haze * 0.8 + skin);
        }
        // Where the edge cuts the ground, a hot ring on the land.
        if (DomeEdge > 0.001 && depth < 1.0) {
            vec3 q = point - Centre;
            float height = dot(q, Up);
            float d = length(q - Up * height);
            float ring = exp(-abs(d - DomeRadius) / 1.2) * exp(-abs(height) / 3.0);
            colour += mix(vec3(0.55, 0.9, 1.0), vec3(1.0), DomeHeat) * ring * DomeEdge * 2.0;
        }
    }

    // The ball, over the dome.
    if (BallRadius > 0.02) {
        vec2 hit = sphere(dir, BallCentre, BallRadius);
        float along = dot(BallCentre, dir);
        vec3 ball = vec3(0.0);
        float ballA = 0.0;
        if (hit.x <= hit.y && hit.x > 0.0 && hit.x < scene) {
            vec3 n = (dir * hit.x - BallCentre) / BallRadius;
            float rim = 1.0 - clamp(dot(n, -dir), 0.0, 1.0);
            float swirl = noise(n * 3.0 + vec3(0.0, Time * 0.02, Time * 0.013)) * 0.6 + noise(n * 7.0 - vec3(Time * 0.03)) * 0.4;
            vec3 core = vec3(0.012, 0.02, 0.06) + vec3(0.02, 0.05, 0.12) * swirl * swirl;
            vec3 edge = vec3(0.2, 0.7, 1.0) * (pow(rim, 4.0) * 2.2 + pow(rim, 14.0) * 4.0) * BallRim;
            ballA = BallCore;
            ball = core * BallCore + edge;
        } else if (along > 0.0 && along < scene) {
            float miss = length(BallCentre - dir * along) - BallRadius;
            if (miss > 0.0) ball = vec3(0.18, 0.62, 1.0) * exp(-miss / (0.22 * BallRadius)) * BallRim * 0.9;
        }
        colour = ball + colour * (1.0 - ballA);
        alpha = ballA + alpha * (1.0 - ballA);
    }

    fragColor = vec4(colour, alpha);
}
