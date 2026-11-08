#version 150
uniform sampler2D Atlas;
uniform sampler2D Shadow;
uniform vec3 Origin;
uniform vec3 Direction;
uniform vec3 LightRight;
uniform vec3 LightUp;
uniform mat4 LightProjection;
uniform vec3 LightColor;
uniform float Tangent;
uniform float Range;
uniform float Strength;
in vec2 uv;
in vec3 normal;
in vec3 tint;
in vec3 position;
in vec4 lightClip;
out vec4 color;
void main() {
    vec3 depth = lightClip.xyz / lightClip.w * 0.5 + 0.5;
    // Use world geometry before discarding any fragments. Differencing near-1 depth loses its low bits.
    vec3 planeNormal = normalize(cross(dFdx(position), dFdy(position)));
    vec4 texel = texture(Atlas, uv);
    if (texel.a < 0.1 || lightClip.w <= 0.0) discard;
    vec3 offset = position - Origin;
    float along = dot(offset, Direction);
    if (along <= 0.0 || along >= Range) discard;
    float radius = max(0.08, along * Tangent);
    float radial = sqrt(max(0.0, dot(offset, offset) - along * along)) / radius;
    float edge = clamp((1.0 - radial) / 0.35, 0.0, 1.0);
    float reach = clamp((Range - along) / max(1.0, Range * 0.2), 0.0, 1.0);
    float facing = max(0.0, dot(normalize(normal), -normalize(offset)));
    float opacity = clamp(edge * edge * (3.0 - 2.0 * edge) * reach * Strength * facing * 3.0, 0.0, 1.0);
    if (opacity <= 0.0) discard;
    if (depth.x < 0.0 || depth.x > 1.0 || depth.y < 0.0 || depth.y > 1.0) discard;
    ivec2 size = textureSize(Shadow, 0);
    ivec2 samplePixel = clamp(ivec2(depth.xy * vec2(size)), ivec2(0), size - 1);
    vec2 sampleCenter = (vec2(samplePixel) + 0.5) / vec2(size);
    // Intersect the shadow texel's central ray with this receiver plane, without moving thin surfaces.
    vec2 rayOffset = (sampleCenter * 2.0 - 1.0) * Tangent;
    vec3 sampleRay = Direction + rayOffset.x * LightRight + rayOffset.y * LightUp;
    float denominator = dot(planeNormal, sampleRay);
    if (abs(denominator) < 1e-7) discard;
    float distance = dot(planeNormal, offset) / denominator;
    vec4 receiverClip = LightProjection * vec4(Origin + distance * sampleRay, 1.0);
    float receiverDepth = receiverClip.z / receiverClip.w * 0.5 + 0.5;
    if (receiverDepth - 0.00000024 > texelFetch(Shadow, samplePixel, 0).r) discard;
    color = vec4(texel.rgb * tint * LightColor, opacity);
}
