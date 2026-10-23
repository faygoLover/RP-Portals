#version 150

// Surface of an RP Portals portal: shape mask (rect / rounded / oval), base colour, live view of the other side
// (Sampler0 at screen coordinates) or a photo of it (Sampler0 with parallax), layered volumetric fog, ripple, rim.
// Portal space: p = position on the plane from its centre (x right, y up, in blocks); CamLocal = camera in the
// same space (z > 0 in front of the face).

uniform sampler2D Sampler0;
uniform vec4 Color;
uniform vec4 RimColor;
uniform float Rim;
uniform float Shape;
uniform float Corner;
uniform vec2 Size;
uniform float Time;
uniform float Ripple;
uniform float Fog;
uniform float Light;
uniform vec2 ScreenSize;
uniform float HasView;
uniform float Photo;
uniform float PhotoDepth;
uniform vec3 CamLocal;

in vec2 texCoord0;
out vec4 fragColor;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = p * 2.03 + vec2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

float shapeDist(vec2 p) {
    vec2 h = Size * 0.5;
    if (Shape > 1.5) {
        float k = min(h.x, h.y);
        return (length(p / h) - 1.0) * k;
    }
    float r = Shape > 0.5 ? Corner * min(Size.x, Size.y) : 0.0;
    vec2 q = abs(p) - h + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// Where the view ray through p meets a plane parallel to the portal at depth d (d > 0 behind the face).
vec2 atDepth(vec2 p, float d) {
    float cz = max(abs(CamLocal.z), 0.05);
    return CamLocal.xy + (p - CamLocal.xy) * (cz + d) / cz;
}

void main() {
    vec2 p = (texCoord0 - 0.5) * Size;
    float d = shapeDist(p);
    float aa = max(fwidth(d), 0.002);
    if (d > aa) discard;
    float inside = 1.0 - smoothstep(-aa, aa, d);

    vec2 wobble = vec2(0.0);
    if (Ripple > 0.0) {
        wobble = vec2(sin(p.y * 5.0 + Time * 2.1) + sin(p.x * 3.0 - Time * 1.3),
                      cos(p.x * 5.0 + Time * 1.7) + cos(p.y * 3.5 + Time * 0.9)) * 0.5;
    }

    vec3 col;
    float alpha;
    if (HasView > 0.5) {
        col = texture(Sampler0, gl_FragCoord.xy / ScreenSize + wobble * 0.004 * Ripple).rgb;
        alpha = 1.0;
    } else if (Photo > 0.5) {
        // The photo hangs PhotoDepth blocks behind the face and is that much bigger: it shifts like a window view.
        float spread = 1.0 + PhotoDepth / 3.0;
        vec2 q = atDepth(p, PhotoDepth) + wobble * 0.02 * Ripple;
        vec2 uv = vec2(0.5 + q.x / (Size.x * spread), 0.5 - q.y / (Size.y * spread));
        col = texture(Sampler0, clamp(uv, 0.002, 0.998)).rgb;
        float edge = max(abs(uv.x - 0.5), abs(uv.y - 0.5));
        col = mix(col, Color.rgb, smoothstep(0.45, 0.6, edge));
        alpha = 1.0;
    } else {
        col = Color.rgb * (1.0 + (noise(p * 3.0 + wobble + Time * 0.2) - 0.5) * 0.25 * Ripple);
        alpha = Color.a;
    }

    if (Fog > 0.0) {
        // Volume: five sheets of drifting fog from in front of the face to deep behind it, each seen through
        // the same pixel from the camera, so the sheets slide against each other as you move.
        float dens = 0.0;
        float wsum = 0.0;
        for (int i = 0; i < 5; i++) {
            float fi = float(i);
            float depth = -0.25 + fi * 0.4;
            vec2 q = atDepth(p, depth) * (1.05 + 0.18 * fi) + wobble * 0.12;
            vec2 drift = vec2(Time * (0.06 + 0.025 * fi), -Time * (0.04 + 0.02 * fi)) + vec2(fi * 17.3, fi * 5.1);
            float n = fbm(q * 1.15 + drift);
            n = mix(n, fbm(q * 2.6 - drift * 1.3), 0.4);
            float w = 1.0 / (1.0 + fi * 0.45);
            dens += n * w;
            wsum += w;
        }
        dens /= wsum;
        vec3 fogCol = mix(Color.rgb, vec3(0.93, 0.94, 0.96), HasView > 0.5 || Photo > 0.5 ? 0.3 : 0.5);
        float glowBand = smoothstep(0.35, 0.8, dens);
        vec3 fc = fogCol * (0.72 + 0.55 * dens) + glowBand * 0.12;
        // Fills the whole frame: no clear gaps at the edge, a touch denser right at the rim.
        float amount = clamp(Fog * (0.6 + 0.55 * dens) + Fog * 0.15 * smoothstep(-0.3, 0.0, d), 0.0, 1.0);
        col = mix(col, fc, amount);
        alpha = max(alpha, amount);
    }

    if (Rim > 0.0) {
        float r = smoothstep(-Rim - aa, -Rim + aa, d);
        col = mix(col, RimColor.rgb, r);
        alpha = max(alpha, r * RimColor.a);
    }

    fragColor = vec4(col * Light, alpha * inside);
}
