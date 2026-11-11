#version 150
in vec3 Position;
in vec2 UV;
in vec3 Normal;
in vec3 Tint;
in vec3 GeomNormal;
uniform mat4 ViewProjection;
uniform mat4 LightProjection;
uniform vec3 SectionOffset;
out vec2 uv;
out vec3 normal;
out vec3 tint;
out vec3 position;
flat out vec4 receiverPlane;
out vec4 lightClip;
void main() {
    position = Position + SectionOffset;
    receiverPlane = vec4(GeomNormal, dot(GeomNormal, position));
    gl_Position = ViewProjection * vec4(Position, 1.0);
    lightClip = LightProjection * vec4(position, 1.0);
    uv = UV;
    normal = Normal;
    tint = Tint;
}
