#version 300 es
precision highp float;
precision highp int;

// Dot — Mitchell downscale, horizontal pass (GL-CHAIN-SPEC §6).
// Mitchell-Netravali (B=C=1/3), widened by the downscale factor s,
// separable: this pass downscales horizontally (8 taps at s=2), the
// vertical pass downscales vertically. Weights are normalised per
// output pixel after edge clamping.
//
//   w(t) = (7|t|^3 - 12|t|^2 + 16/3) / 6              |t| < 1
//   w(t) = (-(7/3)|t|^3 + 12|t|^2 - 20|t| + 32/3)/6  1 <= |t| < 2
//   w(t) = 0                                          |t| >= 2
// Check values: w(0) = 16/18, w(1) = 1/18, w(2) = 0.
//
// Note on the "no negative lobes" claim: the Mitchell kernel as
// written above does dip slightly below zero (w(1.5) = -0.035, the
// largest-magnitude negative weight; the step-edge overshoot this
// causes is the 1.8% figure §6.1 itself cites, against Lanczos-3's
// 9.4%). The lobe is small and bounded (|w| <= 0.04); the kernel is
// implemented faithfully, not clamped, and the test asserts the exact
// formula values plus the bound — not "no negative weights".
//
// Colour space: the input is sRGB (sharp-bilinear's output). Each
// sampled texel is decoded to linear light, filtered there, and the
// pass outputs linear light; the vertical pass encodes back to sRGB
// at its end. The intermediate target therefore holds linear values
// and wants the RGBA16F pool format.
// SPDX-License-Identifier: GPL-3.0-or-later

uniform sampler2D Texture;
uniform vec2 TextureSize;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform int FrameCount;
uniform int FrameDirection;
uniform float Downscale; // s: input width / output width (2.0 for Dot)

in vec2 vTexCoord;
out vec4 FragColor;

float mitchell(float t) {
    float a = abs(t);
    if (a < 1.0) return (7.0 * a * a * a - 12.0 * a * a + 16.0 / 3.0) / 6.0;
    if (a < 2.0) return (-(7.0 / 3.0) * a * a * a + 12.0 * a * a - 20.0 * a + 32.0 / 3.0) / 6.0;
    return 0.0;
}
float srgbDecode(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}
vec3 srgbDecode3(vec3 c) {
    return vec3(srgbDecode(c.r), srgbDecode(c.g), srgbDecode(c.b));
}

void main() {
    float s = Downscale;
    // Output pixel index (x) at this fragment centre.
    float xOut = vTexCoord.x * OutputSize.x - 0.5;
    // Input coordinate of the output pixel centre.
    float xIn = (xOut + 0.5) * s - 0.5;
    int y = int(vTexCoord.y * InputSize.y);
    int wIn = int(InputSize.x);
    // 8 taps covering the widened support (|t| < 2, i.e. |dx| < 2s).
    int base = int(floor(xIn)) - 3;
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int j = 0; j < 8; j++) {
        int i = base + j;
        float w = mitchell(((float(i) + 0.5) - xIn) / s);
        int ci = clamp(i, 0, wIn - 1);
        vec3 lin = srgbDecode3(texelFetch(Texture, ivec2(ci, y), 0).rgb);
        acc += w * lin;
        wsum += w;
    }
    // Normalise after edge clamping (Lomont's rule).
    FragColor = vec4(acc / wsum, 1.0);
}
