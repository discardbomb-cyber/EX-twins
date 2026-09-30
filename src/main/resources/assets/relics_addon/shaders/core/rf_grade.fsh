#version 150

// RF Armageddon's grading of the finished world. As the ball hangs over its target the colour drains out of the
// world (what is drawn after keeps its own: the ball's rim, its lightning, the dome of glass); the atomic flash floods
// it white; then everything solid is a black silhouette on a white sky with a violet fringe where the two meet, and
// behind the shock front running out over the land the ground is raked by rays of white while dark rays converge on
// the blast across the sky; then the colour comes back. A pale ring of dust marks the front on the land throughout.
uniform sampler2D Sampler0;   // the world as it stands, copied
uniform sampler2D Sampler1;   // its depth
uniform mat4 InverseProj;
uniform vec2 TargetSize;
uniform vec3 Centre;          // where the ball met the ground, in view space
uniform vec3 Up;              // the world's axes, in view space
uniform vec3 East;
uniform vec3 North;
uniform float Grey;           // how much of the colour has drained (0..1)
uniform float Dim;            // how much darker the drained world is
uniform float Silhouette;     // 0 the world as it is, 1 black shapes on white
uniform float Flood;          // the flash's white over everything
uniform float ShockRadius;    // how far the shock front has run
uniform float Shock;          // and how strongly it shows
uniform vec2 CentreUv;        // the blast on the screen
uniform float Aspect;         // the screen's width over its height

in vec3 viewPos;

out vec4 fragColor;

const float TAU = 6.28318530718;

float open(vec2 uv) {
    return texture(Sampler1, uv).r >= 1.0 ? 1.0 : 0.0;
}

void main() {
    vec2 uv = gl_FragCoord.xy / TargetSize;
    vec3 scene = texture(Sampler0, uv).rgb;
    float depth = texture(Sampler1, uv).r;
    bool solid = depth < 1.0;

    float luma = dot(scene, vec3(0.299, 0.587, 0.114));
    vec3 colour = mix(scene, vec3(luma) * vec3(0.94, 0.97, 1.03), Grey) * (1.0 - Dim);

    // Where this point of the land lies from the blast, and how near the shock front is to it.
    float front = 0.0, within = 0.0, ray = 0.0;
    if (solid && Shock > 0.001) {
        vec4 point = InverseProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        vec3 p = point.xyz / point.w - Centre;
        vec2 level = vec2(dot(p, East), dot(p, North));
        float d = length(level);
        front = exp(-abs(d - ShockRadius) / 3.0) * Shock;
        within = (1.0 - smoothstep(ShockRadius - 24.0, ShockRadius + 4.0, d)) * smoothstep(8.0, 34.0, d) * Shock;
        float angle = atan(level.y, level.x);
        float rays = fract(angle / TAU * 48.0 + 0.18 * sin(angle * 7.0));
        ray = smoothstep(0.36, 0.5, rays) * (1.0 - smoothstep(0.5, 0.64, rays));
        // Out of the silhouettes the front is a pale ring of dust running over the land.
        colour = mix(colour, vec3(0.78, 0.8, 0.84), 0.35 * front * (1.0 - Silhouette));
    }

    if (Silhouette > 0.001) {
        // How much open sky lies round this point, close by and a little further: the edges of things.
        vec2 px = 1.0 / TargetSize;
        float near = 0.0, far = 0.0;
        for (int k = 0; k < 8; k++) {
            float a = float(k) * TAU / 8.0;
            vec2 d = vec2(cos(a), sin(a));
            near += open(uv + d * px * 2.0);
            far += open(uv + d * px * 5.0);
        }
        near /= 8.0;
        far /= 8.0;
        vec3 inked;
        if (solid) {
            // Black, with a violet fringe where the sky is close; the land the front has passed raked by rays of white.
            inked = mix(vec3(0.0), vec3(0.34, 0.18, 0.7), clamp(near * 1.4 + far * 0.6, 0.0, 1.0) * 0.85);
            inked = mix(inked, vec3(1.0), clamp(ray * within * 0.85 + front * 0.9, 0.0, 1.0));
        } else {
            // White, faintly violet where a black shape is near, dark rays across it converging on the blast.
            inked = mix(vec3(1.0), vec3(0.8, 0.74, 0.98), clamp((1.0 - near) * 1.2 + (1.0 - far) * 0.5, 0.0, 1.0));
            vec2 fromCentre = (uv - CentreUv) * vec2(Aspect, 1.0);
            float angle = atan(fromCentre.y, fromCentre.x);
            float rays = fract(angle / TAU * 64.0 + 0.25 * sin(angle * 5.0 + 1.3));
            float dark = smoothstep(0.43, 0.5, rays) * (1.0 - smoothstep(0.5, 0.57, rays));
            inked = mix(inked, vec3(0.08, 0.05, 0.15), Shock * dark * smoothstep(0.12, 0.75, length(fromCentre)) * 0.8);
        }
        colour = mix(colour, inked, Silhouette);
    }

    colour = mix(colour, vec3(1.0), Flood);
    fragColor = vec4(colour, 1.0);
}
