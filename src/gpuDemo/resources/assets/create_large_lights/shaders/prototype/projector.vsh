#version 150
in vec3 Position;
in vec2 UV;
in vec3 Normal;
in vec3 Tint;
uniform mat4 ViewProjection;
uniform mat4 LightProjection;
out vec2 uv;
out vec3 normal;
out vec3 tint;
out vec3 position;
out vec4 lightClip;
void main() {
    gl_Position = ViewProjection * vec4(Position, 1.0);
    lightClip = LightProjection * vec4(Position, 1.0);
    uv = UV;
    normal = Normal;
    tint = Tint;
    position = Position;
}
