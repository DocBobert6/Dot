#version 300 es
precision highp float;
precision highp int;

// Dot's own passthrough vertex shader, shared by the passes Dot writes
// itself (dither-invert, sharp-bilinear, Mitchell H/V, deflicker).
// The MIT libretro passes (ScaleFX, rAA, RCAS) ship their own vertex
// shaders that precompute neighbour offsets; Dot's passes compute their
// offsets in the fragment shader instead, so they need only the plain
// interpolated texcoord.
//
// The renderer binds the same two attributes for every program
// (VertexCoord, TexCoord) and an identity MVPMatrix. Texcoords follow
// the bitmap convention: the uploaded frame's top row is v=0, so the
// quad's top vertices carry v=0 and its bottom vertices v=1; the
// flipped screen pass uses the swapped quad.
// SPDX-License-Identifier: GPL-3.0-or-later

in vec4 VertexCoord;
in vec2 TexCoord;
uniform mat4 MVPMatrix;
out vec2 vTexCoord;

void main() {
    gl_Position = MVPMatrix * VertexCoord;
    vTexCoord = TexCoord;
}
