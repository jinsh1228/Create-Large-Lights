#version 150
uniform sampler2D Atlas;
in vec2 uv;
out vec4 color;
void main() {
    if (texture(Atlas, uv).a < 0.1) discard;
    color = vec4(0.0);
}
