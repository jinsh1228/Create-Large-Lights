#version 150
in vec3 Position;
in vec2 UV;
in vec3 Normal;
in vec3 Tint;
in vec3 GeomNormal;
uniform mat4 ViewProjection;
#ifdef LIGHT_BATCH
uniform mat4 LightProjections[LIGHT_BATCH];
uniform vec3 SectionOffsets[LIGHT_BATCH];
out vec3 localPosition;
flat out vec3 planeNormal;
flat out vec4 planeOffsets;
out vec4 lightClips[LIGHT_BATCH];
#else
uniform mat4 LightProjection;
uniform vec3 SectionOffset;
out vec3 position;
flat out vec4 receiverPlane;
out vec4 lightClip;
#endif
out vec2 uv;
out vec3 normal;
out vec3 tint;
void main() {
#ifdef LIGHT_BATCH
    gl_Position = ViewProjection * vec4(Position, 1.0);
    localPosition = Position;
    planeNormal = GeomNormal;
    for (int i = 0; i < LIGHT_BATCH; i++) {
        vec3 position = Position + SectionOffsets[i];
        planeOffsets[i] = dot(GeomNormal, position);
        lightClips[i] = LightProjections[i] * vec4(position, 1.0);
    }
#else
    position = Position + SectionOffset;
    receiverPlane = vec4(GeomNormal, dot(GeomNormal, position));
    gl_Position = ViewProjection * vec4(position, 1.0);
    lightClip = LightProjection * vec4(position, 1.0);
#endif
    uv = UV;
    normal = Normal;
    tint = Tint;
}
