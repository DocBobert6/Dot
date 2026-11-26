#version 300 es
precision highp float;
precision highp int;

// Dot — gated flicker-transparency (GBI deflicker rule,
// GL-CHAIN-SPEC §7). Blends the current dither-inverted frame with
// the previous dither-inverted frame, but ONLY where the CPU gate
// says the pixel is alternating with period 2 (BlendMask), and only
// at the weight the gate computed (sustain + decay live on the CPU).
//
//   out = (1 - 0.45*m) * frame[n] + 0.45*m * frame[n-1]
//   (m = 1 sustained: 0.55/0.45; m = 0: unblended; decay ramps m
//   from 1 to 0 over 2-4 frames when the alternation stops.)
//
// The blend is computed in linear light and encoded back to sRGB.
// BlendMask is an R8 texture holding m in [0,1]; the renderer
// uploads it per frame from the CPU gate's mask. PrevTexture is the
// stored dither-inverted frame n-1 (a persistent target the renderer
// swaps each frame, never a pool target in flight).
//
// Phase 1 limitation (GL-CHAIN-SPEC §7.3, decided): Dot Phase 1 has
// no scroll data from the core, so there is no scroll compensation —
// a scrolling periodic background can false-positive (the sustain
// gate bounds the damage). The device A/B hunts false positives;
// scroll compensation is Phase 2 work.
// SPDX-License-Identifier: GPL-3.0-or-later

uniform sampler2D Texture;     // current dither-inverted frame (unit 0)
uniform sampler2D PrevTexture; // previous dither-inverted frame (unit 1)
uniform sampler2D BlendMask;   // per-pixel blend amount m (unit 2)
uniform vec2 TextureSize;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform int FrameCount;
uniform int FrameDirection;

in vec2 vTexCoord;
out vec4 FragColor;

float srgbDecode(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}
float srgbEncode(float lin) {
    return lin <= 0.0031308 ? 12.92 * lin
        : 1.055 * pow(lin, 1.0 / 2.4) - 0.055;
}
vec3 srgbDecode3(vec3 c) {
    return vec3(srgbDecode(c.r), srgbDecode(c.g), srgbDecode(c.b));
}
vec3 srgbEncode3(vec3 lin) {
    return vec3(srgbEncode(lin.r), srgbEncode(lin.g), srgbEncode(lin.b));
}

void main() {
    ivec2 xy = ivec2(vTexCoord * InputSize);
    ivec2 sz = ivec2(InputSize);
    ivec2 c = clamp(xy, ivec2(0), sz - 1);
    vec3 cur = texelFetch(Texture, c, 0).rgb;
    vec3 prv = texelFetch(PrevTexture, c, 0).rgb;
    float m = texelFetch(BlendMask, c, 0).r;
    vec3 linCur = srgbDecode3(cur);
    vec3 linPrv = srgbDecode3(prv);
    float w = 0.45 * m;
    vec3 outLin = (1.0 - w) * linCur + w * linPrv;
    FragColor = vec4(srgbEncode3(outLin), 1.0);
}
