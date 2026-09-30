#version 150

// A quad over the whole screen; each corner carries its view ray, as the view-space point on the far plane.
in vec3 Position;

uniform mat4 InverseProj;

out vec3 viewPos;

void main() {
    vec4 far = InverseProj * vec4(Position.xy, 1.0, 1.0);
    viewPos = far.xyz / far.w;
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
