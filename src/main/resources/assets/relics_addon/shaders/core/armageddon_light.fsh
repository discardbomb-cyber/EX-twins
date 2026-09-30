#version 150

// The Armageddon blast's light on the world, as a multiplier on the finished world (the blend takes the
// world times (light + what is kept)): dark red in the silence before the dome of light, washed bright
// violet-white wherever the dome of light has swept, hot magenta after, night round the pillar of light, and
// an orange dusk as it goes out; the sky apart from the land. Near the blast a light of its own adds to it.
uniform sampler2D Sampler1;   // depth of the scene
uniform mat4 InverseProj;
uniform vec2 TargetSize;
uniform vec3 Centre;          // where the blast burst, on the ground, in view space
uniform vec3 Up;              // the world's up, in view space
uniform vec3 World;           // what the land is multiplied by
uniform vec3 Sky;             // what the sky is multiplied by
uniform vec3 Inside;          // what the land within the dome of light is multiplied by
uniform float Front;          // the dome of light's radius
uniform vec3 Glow;            // the light near the blast, and how far it reaches
uniform float Reach;

in vec3 viewPos;

out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / TargetSize;
    float depth = texture(Sampler1, uv).r;
    vec3 times;
    if (depth < 1.0) {
        vec4 point = InverseProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        vec3 p = point.xyz / point.w - Centre;
        float up = dot(p, Up), out_ = length(p - Up * up), d = length(p);
        float within = Front > 0.1 ? 1.0 - smoothstep(Front - 25.0, Front + 12.0, d) : 0.0;
        times = mix(World, Inside, within) + Glow / (1.0 + d * d / (Reach * Reach));
    } else {
        times = Sky;
    }
    // Split into a multiplier of up to one and a share of the world added back, so it can brighten as well as darken.
    float extra = clamp(max(times.r, max(times.g, times.b)) - 1.0, 0.0, 1.0);
    fragColor = vec4(max(times - vec3(extra), vec3(0.0)), extra);
}
