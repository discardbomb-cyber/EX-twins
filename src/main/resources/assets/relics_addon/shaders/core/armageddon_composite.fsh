#version 150

// Lays the blast's volume, marched at a fraction of the screen's size, over the world: its light added
// (rgb), the world behind it dimmed by what it lets through (alpha, applied by the blend). A light blur
// smooths away the grain of the march.
uniform sampler2D Sampler0;
uniform vec2 ScreenSize;
uniform vec2 TexelSize;       // one texel of the reduced volume, in uv

out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec4 sum = texture(Sampler0, uv) * 0.28;
    sum += texture(Sampler0, uv + vec2(TexelSize.x, 0.0)) * 0.12;
    sum += texture(Sampler0, uv - vec2(TexelSize.x, 0.0)) * 0.12;
    sum += texture(Sampler0, uv + vec2(0.0, TexelSize.y)) * 0.12;
    sum += texture(Sampler0, uv - vec2(0.0, TexelSize.y)) * 0.12;
    sum += texture(Sampler0, uv + TexelSize) * 0.06;
    sum += texture(Sampler0, uv - TexelSize) * 0.06;
    sum += texture(Sampler0, uv + vec2(TexelSize.x, -TexelSize.y)) * 0.06;
    sum += texture(Sampler0, uv + vec2(-TexelSize.x, TexelSize.y)) * 0.06;
    fragColor = sum;
}
