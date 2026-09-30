#version 150

// The pull of a black hole on the already-rendered world behind it. Light passing near the horizon is
// bent round it (a point-mass lens) and swallowed: the nearer a ray passes the hole and the longer it
// runs by it, the darker. What stands in front of the hole is neither bent nor darkened.
uniform sampler2D Sampler0;   // colour of the scene
uniform sampler2D Sampler1;   // depth of the scene
uniform mat4 ProjMat;
uniform mat4 InverseProj;
uniform vec2 ScreenSize;
uniform vec3 Centre;          // the hole's middle, in view space
uniform float Horizon;        // radius of the horizon, in blocks
uniform float Einstein;       // radius of the lens's Einstein ring, in blocks
uniform float Reach;          // how far out from the middle space is pulled, in blocks
uniform float Darkness;       // optical depth of a ray grazing the horizon
uniform float Halo;           // width of the swallowing halo round the hole, in blocks

in vec3 viewPos;

out vec4 fragColor;

// Distance from the eye to the surface of the scene seen at uv (the sky is very far).
float sceneDistance(vec2 uv) {
    float depth = texture(Sampler1, uv).r;
    if (depth >= 1.0) {
        return 1.0e6;
    }
    vec4 point = InverseProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return length(point.xyz / point.w);
}

vec2 toScreen(vec3 p) {
    vec4 clip = ProjMat * vec4(p, 1.0);
    return clip.xy / max(clip.w, 1.0e-4) * 0.5 + 0.5;
}

// The error function, to about 1e-3.
float erfApprox(float x) {
    float a = abs(x);
    float t = 1.0 / (1.0 + 0.47047 * a);
    float y = 1.0 - (0.3480242 * t - 0.0958798 * t * t + 0.7478556 * t * t * t) * exp(-a * a);
    return sign(x) * y;
}

void main() {
    vec3 dir = normalize(viewPos);
    float t0 = dot(Centre, dir);
    if (t0 <= 0.0) {
        discard;
    }
    // From the middle to where this ray passes it closest, and how close that is.
    vec3 offset = dir * t0 - Centre;
    float b = length(offset);
    if (b >= Reach) {
        discard;
    }
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    float here = sceneDistance(uv);
    // Only the world behind the hole is bent; the change eases in over the horizon's depth.
    float behind = smoothstep(t0 - Horizon, t0 + Horizon, here);

    // The lens: the image at distance b from the middle shows what lies at b - E^2/b (on the far side
    // when that is negative), easing back to the plain image towards the reach.
    float ease = 1.0 - smoothstep(Reach * 0.4, Reach, b);
    float source = b - Einstein * Einstein / max(b, 1.0e-3) * ease;
    vec3 sourcePoint = Centre + offset / max(b, 1.0e-4) * source;
    vec2 sourceUv = clamp(toScreen(sourcePoint), vec2(0.0005), vec2(0.9995));
    vec3 bent = texture(Sampler0, sourceUv).rgb;
    // What stands in or before the hole (the creature it holds) cannot be seen round it: it is swallowed.
    vec3 sourceDir = normalize(sourcePoint);
    float sourceMiddle = dot(Centre, sourceDir);
    bent *= smoothstep(sourceMiddle - Horizon * 0.25, sourceMiddle + Horizon, sceneDistance(sourceUv));
    vec3 colour = mix(texture(Sampler0, uv).rgb, bent, behind);

    // Swallowed light: the optical depth of the ray through a gaussian halo round the hole, counted
    // only as far as the surface it ends on.
    float ends = min(here, t0 + 4.0 * Halo);
    float along = 0.5 * (erfApprox((ends - t0) / Halo) + erfApprox(t0 / Halo));
    float tau = Darkness * exp(-(b * b - Horizon * Horizon) / (Halo * Halo)) * max(along, 0.0);
    tau *= 1.0 - smoothstep(Reach * 0.8, Reach, b);
    fragColor = vec4(colour * exp(-tau), 1.0);
}
