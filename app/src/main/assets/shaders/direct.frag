#version 300 es
precision highp float;
precision highp int;

// Dot — the direct-pattern fragment shader (Phase 1.5).
//
// The checkerboard is computed here, in the shader, from the
// interpolated texcoord: NO texture, NO FBO pass, NO chain pass.
// The direct pattern is drawn straight to the default framebuffer
// with dot-common.vert, so one on-device test isolates the
// surface/swap/composition path from the picture chain: if this
// pattern shows and the game (or the through-chain pattern) does
// not, the chain's output is at fault; if this pattern does not
// show either, the surface path itself is broken.
//
// 20x18 cells over the on-screen rect, magenta / cyan — the same
// unmistakable colours as the through-chain test pattern, so the
// two are never confused with a game picture (and the direct one
// is true cyan: the through-chain pattern's cyan goes through the
// ARGB upload, whose little-endian byte order swaps R and B and
// turns it yellow — a colour-fidelity note, not a visibility one).
// Copyright (c) 2026 Dot contributors. All rights reserved.

in vec2 vTexCoord;
out vec4 FragColor;

void main() {
    vec2 cell = floor(vTexCoord * vec2(20.0, 18.0));
    float parity = mod(cell.x + cell.y, 2.0);
    vec3 magenta = vec3(1.0, 0.0, 1.0);
    vec3 cyan = vec3(0.0, 1.0, 1.0);
    FragColor = vec4(parity < 0.5 ? magenta : cyan, 1.0);
}
