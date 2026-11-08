#version 150
uniform sampler2D Atlas;
uniform int OpaqueGeometry;
uniform vec3 Origin;
uniform vec3 Direction;
uniform vec3 LightRight;
uniform vec3 LightUp;
uniform float Tangent;
uniform float ShadowSize;
uniform float Near;
in vec2 uv;
flat in vec4 receiverPlane;
out vec4 color;
void main() {
    if (OpaqueGeometry == 0 && texture(Atlas, uv).a < 0.1) discard;
    // Exactly the receiver pass's plane calculation, at this shadow texel's centre.
    vec2 sampleCenter = gl_FragCoord.xy / ShadowSize;
    vec2 rayOffset = (sampleCenter * 2.0 - 1.0) * Tangent;
    vec3 sampleRay = Direction + rayOffset.x * LightRight + rayOffset.y * LightUp;
    float denominator = dot(receiverPlane.xyz, sampleRay);
    if (abs(denominator) < 1e-7) discard;
    float distance = (receiverPlane.w - dot(receiverPlane.xyz, Origin)) / denominator;
    if (distance <= 0.0) discard;
    gl_FragDepth = Near / (distance * dot(sampleRay, Direction));
    color = vec4(0.0);
}
