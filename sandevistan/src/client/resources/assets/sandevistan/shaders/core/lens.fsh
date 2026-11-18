#version 330

// «Сингулярность»: искажение картинки мира вокруг чёрной дыры.
// 1) Гравитационная линза: точка на расстоянии r от центра показывает то, что «за дырой» на расстоянии r - k*rs²/r
//    (внутри кольца Эйнштейна изображение зеркалится — как на настоящих снимках).
// 2) Закручивание пространства (дыра вращается) — сильнее у горизонта.
// 3) Ударная волна сверхновой — кольцо, преломляющее свет.
// 4) Небольшое разложение цвета у горизонта и чёрный горизонт событий.

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform LensInfo {
    vec4 Hole;  // xy — центр (0..1 экрана), z — радиус горизонта (в долях высоты экрана), w — сила линзы
    vec4 Ring;  // x — радиус кольца волны, y — его ширина, z — сила, w — ширина/высота экрана
    vec4 Misc;  // x — время, y — закрутка, z — рисовать горизонт (1/0), w — не используется
};

out vec4 fragColor;

vec2 rotate2(vec2 v, float a) {
    float c = cos(a), s = sin(a);
    return vec2(c * v.x - s * v.y, s * v.x + c * v.y);
}

void main() {
    float aspect = Ring.w;
    vec2 d = texCoord - Hole.xy;
    d.x *= aspect;
    float r = length(d);
    float rs = Hole.z;
    vec2 uv = texCoord;

    if (rs > 0.0) {
        float rr = max(r, 1e-4);
        vec2 dir = d / rr;
        float src = rr - Hole.w * rs * rs / rr;
        float fade = 1.0 - smoothstep(rs * 5.0, rs * 10.0, rr);
        vec2 sd = dir * src;
        sd = rotate2(sd, Misc.y * rs / max(rr, rs) * fade + 0.05 * sin(Misc.x * 0.7) * fade * rs / max(rr, rs));
        vec2 lensed = sd;
        lensed.x /= aspect;
        uv = mix(texCoord, Hole.xy + lensed, fade);
    }

    if (Ring.z > 0.0) {
        float x = (r - Ring.x) / max(Ring.y, 1e-4);
        float bump = exp(-x * x) * x;
        vec2 dir2 = r > 1e-4 ? d / r : vec2(0.0);
        vec2 off = dir2 * bump * Ring.z;
        off.x /= aspect;
        uv += off;
    }

    float ca = rs > 0.0 ? 0.006 * (1.0 - smoothstep(rs * 1.1, rs * 4.0, r)) : 0.0;
    ca += Ring.z > 0.0 ? 0.004 * exp(-pow((r - Ring.x) / max(Ring.y, 1e-4), 2.0)) : 0.0;
    vec2 cdir = r > 1e-4 ? d / r : vec2(0.0);
    cdir.x /= aspect;
    vec2 lo = vec2(0.0005), hi = vec2(0.9995);
    vec3 col;
    col.r = texture(InSampler, clamp(uv + cdir * ca, lo, hi)).r;
    col.g = texture(InSampler, clamp(uv, lo, hi)).g;
    col.b = texture(InSampler, clamp(uv - cdir * ca, lo, hi)).b;

    if (rs > 0.0 && Misc.z > 0.5) {
        float h = 1.0 - smoothstep(rs * 0.96, rs * 1.02, r);
        col = mix(col, vec3(0.0), h);
        // Тень у самого горизонта — свет «не успевает» уйти.
        float shade = 1.0 - smoothstep(rs * 1.0, rs * 1.6, r);
        col *= 1.0 - 0.55 * shade;
    }
    fragColor = vec4(col, 1.0);
}
