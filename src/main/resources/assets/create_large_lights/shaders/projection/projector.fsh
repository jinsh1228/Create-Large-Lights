#version 150
uniform sampler2D Atlas;
uniform sampler2D Shadow0;
uniform sampler2D Shadow1;
uniform sampler2D Shadow2;
uniform sampler2D Shadow3;
uniform vec3 Origins[LIGHT_BATCH];
uniform vec3 SectionOffsets[LIGHT_BATCH];
uniform vec3 Directions[LIGHT_BATCH];
uniform vec3 LightRights[LIGHT_BATCH];
uniform vec3 LightUps[LIGHT_BATCH];
uniform vec3 LightColors[LIGHT_BATCH];
uniform float Tangents[LIGHT_BATCH];
uniform float Ranges[LIGHT_BATCH];
uniform float Strengths[LIGHT_BATCH];
uniform float Radii[LIGHT_BATCH];
uniform float Near;
uniform int ActiveMask;
in vec2 uv;
in vec3 normal;
in vec3 tint;
in vec3 localPosition;
flat in vec3 planeNormal;
flat in vec4 planeOffsets;
in vec4 lightClips[LIGHT_BATCH];
out vec4 color;
#define Origin Origins[light]
#define Direction Directions[light]
#define LightRight LightRights[light]
#define LightUp LightUps[light]
#define LightColor LightColors[light]
#define Tangent Tangents[light]
#define Range Ranges[light]
#define Strength Strengths[light]
#define Radius Radii[light]
float shadowDepth(int light, ivec2 pixel) {
    // GLSL 1.50 requires constant sampler indices.
    if (light == 0) return texelFetch(Shadow0, pixel, 0).r;
    if (light == 1) return texelFetch(Shadow1, pixel, 0).r;
    if (light == 2) return texelFetch(Shadow2, pixel, 0).r;
    return texelFetch(Shadow3, pixel, 0).r;
}
vec4 illuminate(int light) {
    vec3 position = localPosition + SectionOffsets[light];
    vec4 receiverPlane = vec4(planeNormal, planeOffsets[light]);
    vec4 lightClip = lightClips[light];
    vec3 depth = lightClip.xyz / lightClip.w * 0.5 + 0.5;
    if (lightClip.w <= 0.0) return vec4(0.0);
    vec3 offset = position - Origin;
    if (dot(normal, -offset) <= 0.0) return vec4(0.0);
    float along = dot(offset, Direction);
    if (along <= 0.0 || along >= Range) return vec4(0.0);
    float radius = min(Radius, max(0.08, along * Tangent));
    float squaredRadial = max(0.0, dot(offset, offset) - along * along);
    if (squaredRadial >= radius * radius) return vec4(0.0);
    float radial = sqrt(squaredRadial) / radius;
    float edge = clamp((1.0 - radial) / 0.35, 0.0, 1.0);
    float reach = clamp((Range - along) / max(1.0, Range * 0.2), 0.0, 1.0);
    float facing = max(0.0, dot(normalize(normal), -normalize(offset)));
    float opacity = clamp(edge * edge * (3.0 - 2.0 * edge) * reach * Strength * facing * 3.0, 0.0, 1.0);
    if (opacity <= 0.0) return vec4(0.0);
    if (depth.x < 0.0 || depth.x > 1.0 || depth.y < 0.0 || depth.y > 1.0) return vec4(0.0);
    ivec2 size = textureSize(Shadow0, 0);
    ivec2 samplePixel = clamp(ivec2(depth.xy * vec2(size)), ivec2(0), size - 1);
    vec2 sampleCenter = (vec2(samplePixel) + 0.5) / vec2(size);
    // Intersect the shadow texel's central ray with this receiver plane, without moving thin surfaces.
    vec2 rayOffset = (sampleCenter * 2.0 - 1.0) * Tangent;
    vec3 sampleRay = Direction + rayOffset.x * LightRight + rayOffset.y * LightUp;
    float denominator = dot(receiverPlane.xyz, sampleRay);
    if (abs(denominator) < 1e-7) return vec4(0.0);
    float distance = (receiverPlane.w - dot(receiverPlane.xyz, Origin)) / denominator;
    if (distance <= 0.0) return vec4(0.0);
    // The float basis is only approximately orthonormal; keep the same along distance as the depth pass.
    float receiverDepth = Near / (distance * dot(sampleRay, Direction));
    // Relative error for the dot/divide chain, rather than a range-dependent world-space offset.
    float tolerance = receiverDepth * 0.00000095367432;
    if (receiverDepth + tolerance < shadowDepth(light, samplePixel)) return vec4(0.0);
    return vec4(LightColor * opacity, opacity * opacity);
}

void main() {
    vec4 sum = vec4(0.0);
    for (int light = 0; light < LIGHT_BATCH; light++) {
        if ((ActiveMask & (1 << light)) != 0) sum += illuminate(light);
    }
    if (sum == vec4(0.0)) discard;
    vec4 texel = texture(Atlas, uv);
    if (texel.a < 0.1) discard;
    // Premultiplied addition keeps each light's opacity clamp and the original alpha contribution.
    color = vec4(texel.rgb * tint * sum.rgb, sum.a);
}
