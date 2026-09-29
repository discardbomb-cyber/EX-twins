#version 150

// Bends the already-rendered scene behind a shield hit wave.
// Red carries the signed wave height (0.5 = flat), alpha the band mask.
uniform sampler2D Sampler0;
uniform vec2 ScreenSize;
uniform float RefractionGain;
uniform vec3 Tint;

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float height = vertexColor.r * 2.0 - 1.0;
    float mask = vertexColor.a;
    if (mask <= 0.002) {
        discard;
    }
    // Screen-space slope of the wave: light is pushed away from the crest like a lens.
    vec2 slope = vec2(dFdx(height), dFdy(height));
    vec2 texel = 1.0 / ScreenSize;
    vec2 uv = gl_FragCoord.xy * texel;
    vec2 offset = slope * RefractionGain * mask * texel;
    // Slight chromatic split sells the distortion without an extra pass.
    float r = texture(Sampler0, uv + offset * 1.08).r;
    float g = texture(Sampler0, uv + offset).g;
    float b = texture(Sampler0, uv + offset * 0.92).b;
    vec3 bent = vec3(r, g, b) * mix(vec3(1.0), Tint, 0.18 * abs(height));
    fragColor = vec4(bent, clamp(mask * 1.15, 0.0, 1.0));
}
