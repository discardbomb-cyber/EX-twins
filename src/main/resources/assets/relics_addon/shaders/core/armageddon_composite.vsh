#version 150

// A quad over the whole screen, given straight in normalised device coordinates.
in vec3 Position;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
