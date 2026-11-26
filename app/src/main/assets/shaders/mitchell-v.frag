#version 300 es
precision highp float;
precision highp int;

// Dot — Mitchell downscale, vertical pass (GL-CHAIN-SPEC §6).
// The second half of the separable Mitchell downscale: downscales
// vertically (8 taps at s=2), filtering in linear light (its input
// is the horizontal pass's linear-light output), and sRGB-encodes
// the result at its end so RCAS runs on sRGB. See mitchell-h.frag
// for the kernel, the negative-lobe note, and the normalisation rule.
// SPDX-License-Identifier: GPL-3.0-or-later

uniform sampler2D Texture;
uniform vec2 TextureSize;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform int FrameCount;
uniform int FrameDirection;
uniform float Downscale; // s: input height / output height (2.0 for Dot)

in vec2 vTexCoord;
out vec4 FragColor;

float mitchell(float t) {
    float a = abs(t);
    if (a < 1.0) return (7.0 * a * a * a - 12.0 * a * a + 16.0 / 3.0) / 6.0;
    if (a < 2.0) return (-(7.0 / 3.0) * a * a * a + 12.0 * a * a - 20.0 * a + 32.0 / 3.0) / 6.0;
    return 0.0;
}
float srgbEncode(float lin) {
    return lin <= 0.0031308 ? 12.92 * lin
        : 1.055 * pow(lin, 1.0 / 2.4) - 0.055;
}
vec3 srgbEncode3(vec3 lin) {
    return vec3(srgbEncode(lin.r), srgbEncode(lin.g), srgbEncode(lin.b));
}

void main() {
    float s = Downscale;
    float yOut = vTexCoord.y * OutputSize.y - 0.5;
    float yIn = (yOut + 0.5) * s - 0.5;
    int x = int(vTexCoord.x * InputSize.x);
    int hIn = int(InputSize.y);
    int base = int(floor(yIn)) - 3;
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int j = 0; j < 8; j++) {
        int i = base + j;
        float w = mitchell(((float(i) + 0.5) - yIn) / s);
        int ci = clamp(i, 0, hIn - 1);
        vec3 lin = texelFetch(Texture, ivec2(x, ci), 0).rgb;
        acc += w * lin;
        wsum += w;
    }
    FragColor = vec4(srgbEncode3(acc / wsum), 1.0);
}
