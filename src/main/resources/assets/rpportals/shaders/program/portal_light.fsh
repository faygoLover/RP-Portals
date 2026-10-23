#version 150

// RP Portals: glowing portals light their surroundings in their own colour. Per pixel: world position from depth,
// distance to the portal rectangle, falloff over the light range, weaker behind the face. Up to 4 portals per pass,
// packed one per matrix column:
//   LightPos[i] = (bottom centre of the portal relative to the camera, power)
//   LightDir[i] = (face normal, width)
//   LightCol[i] = (colour, height)
//   LightExt[i] = (range, both sides 0/1, unused, unused)

uniform sampler2D DiffuseSampler;
uniform sampler2D DiffuseDepthSampler;
uniform mat4 InvProjMat;
uniform mat4 InvViewMat;
uniform mat4 LightPos;
uniform mat4 LightDir;
uniform mat4 LightCol;
uniform mat4 LightExt;
uniform float LightCount;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 src = texture(DiffuseSampler, texCoord);
    float depth = texture(DiffuseDepthSampler, texCoord).r;
    if (depth >= 0.99995 || LightCount < 0.5) {
        fragColor = src;
        return;
    }
    vec4 ndc = vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = InvProjMat * ndc;
    vec3 pos = (InvViewMat * vec4(view.xyz / view.w, 0.0)).xyz;

    vec3 light = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        if (float(i) >= LightCount) break;
        vec3 o = LightPos[i].xyz;
        float power = LightPos[i].w;
        vec3 n = normalize(LightDir[i].xyz);
        float w = LightDir[i].w;
        vec3 c = LightCol[i].rgb;
        float h = LightCol[i].a;
        float range = max(LightExt[i].x, 0.5);
        vec3 r = vec3(n.z, 0.0, -n.x);
        vec3 d = pos - o;
        vec3 l = vec3(dot(d, r), d.y, dot(d, n));
        vec3 q = vec3(clamp(l.x, -w * 0.5, w * 0.5), clamp(l.y, 0.0, h), 0.0);
        float dist = length(l - q);
        float fall = clamp(1.0 - dist / range, 0.0, 1.0);
        fall *= fall;
        float face = l.z >= 0.0 ? 1.0 : mix(0.15, 1.0, LightExt[i].y);
        light += c * fall * face * power;
    }
    vec3 col = src.rgb * (vec3(1.0) + light * 1.8) + light * 0.05;
    fragColor = vec4(col, src.a);
}
