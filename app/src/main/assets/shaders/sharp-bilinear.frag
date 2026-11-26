#version 300 es
precision highp float;
precision highp int;

// Dot — sharp-bilinear final resample (GL-CHAIN-SPEC §5).
// Public-domain sharp-bilinear-simple (rsn8887 after TheMaister):
// an integer prescale combined with one hardware-bilinear tap. The UV
// warp *is* the prescale, so no separate prescale target is needed.
//
//   s = max(floor(outputSize / inputSize), 1)   (per axis)
//   r = 0.5 - 0.5/s                             (region range)
//   p = uv * inputSize; d = fract(p) - 0.5
//   f = (d - clamp(d, -r, r)) * s + 0.5
//   sample at (floor(p) + f) / inputSize, with GL_LINEAR.
//
// Inside a texel (|d| <= r) the warp maps the position to the texel
// centre, so texel interiors stay flat; only where a texel boundary
// falls inside a screen pixel (|d| > r) does the sample move
// off-centre, blending across the boundary in exactly that one screen
// pixel. At integer scales the warp returns texel centres exactly —
// identical to nearest (Dot's 4K target is an exact 5x).
//
// Colour space (GL-CHAIN-SPEC §5.3): the blend happens in linear
// light. The input texture holds linear-light values (ScaleFX pass 4
// decodes the selected colour to linear), the single hardware-bilinear
// tap blends them in linear light, and this pass sRGB-encodes the
// result at its end. RCAS then runs on sRGB.
// Copyright (c) 2026 Dot contributors. All rights reserved.

uniform sampler2D Texture;
uniform vec2 TextureSize;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform int FrameCount;
uniform int FrameDirection;

in vec2 vTexCoord;
out vec4 FragColor;

float srgbEncode(float lin) {
    return lin <= 0.0031308 ? 12.92 * lin
        : 1.055 * pow(lin, 1.0 / 2.4) - 0.055;
}
vec3 srgbEncode3(vec3 lin) {
    return vec3(srgbEncode(lin.r), srgbEncode(lin.g), srgbEncode(lin.b));
}

void main() {
    vec2 s = max(floor(OutputSize / InputSize), vec2(1.0));
    vec2 r = 0.5 - 0.5 / s;
    vec2 p = vTexCoord * InputSize;
    vec2 d = fract(p) - 0.5;
    vec2 f = (d - clamp(d, -r, r)) * s + 0.5;
    vec2 sampleUv = (floor(p) + f) / InputSize;
    // One hardware-bilinear tap, in linear light. The input texture
    // must be bound with GL_LINEAR filtering.
    vec3 lin = texture(Texture, sampleUv).rgb;
    FragColor = vec4(srgbEncode3(lin), 1.0);
}
