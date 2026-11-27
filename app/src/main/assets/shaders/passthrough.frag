#version 300 es
precision highp float;
precision highp int;

// Dot — passthrough fragment shader (Phase 1.6, chain bisection).
// Samples the input texture and outputs it unchanged. The bisection
// bypass draws a chain stage's target straight to the screen through
// this shader (with dot-common.vert), so the stage's output is shown
// by the same known-good path the direct pattern proved — no chain
// pass stands between the stage and the screen, and a black stage
// output cannot be blamed on the final pass.
// Copyright (c) 2026 Dot contributors. All rights reserved.

uniform sampler2D Texture;
in vec2 vTexCoord;
out vec4 FragColor;

void main() {
    FragColor = texture(Texture, vTexCoord);
}
