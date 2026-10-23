#version 150

// Surface of an RP Portals portal: shape mask (rect / rounded / oval), base colour or the live view of the other side
// (Sampler0 sampled at screen coordinates), Dark Souls style fog, ripple, rim.

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

// Signed distance to the portal outline, in blocks (negative inside).
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
        vec2 suv = gl_FragCoord.xy / ScreenSize + wobble * 0.004 * Ripple;
        col = texture(Sampler0, suv).rgb;
        alpha = 1.0;
    } else {
        col = Color.rgb * (1.0 + (noise(p * 3.0 + wobble + Time * 0.2) - 0.5) * 0.25 * Ripple);
        alpha = Color.a;
    }

    if (Fog > 0.0) {
        vec2 fp = p * 1.1 + wobble * 0.15;
        float n = fbm(fp + vec2(Time * 0.11, -Time * 0.07));
        n = mix(n, fbm(fp * 2.3 - vec2(Time * 0.05, Time * 0.13)), 0.45);
        vec3 fogCol = mix(Color.rgb, vec3(0.92, 0.93, 0.95), HasView > 0.5 ? 0.25 : 0.55);
        float amount = Fog * (0.45 + 0.55 * n);
        col = mix(col, fogCol * (0.75 + 0.5 * n), amount);
        if (HasView < 0.5) alpha *= 1.0 - Fog * 0.45 * (1.0 - n);
        // Fog walls fade out towards the edge.
        alpha *= smoothstep(0.0, 0.18 * min(Size.x, Size.y) * Fog + 0.001, -d);
    }

    if (Rim > 0.0) {
        float r = smoothstep(-Rim - aa, -Rim + aa, d);
        col = mix(col, RimColor.rgb, r);
        alpha = max(alpha, r * RimColor.a);
    }

    fragColor = vec4(col * Light, alpha * inside);
}
