#version 300 es
precision highp float;
precision highp int;
// Dot adaptation: ES 3.00 split of the combined RetroArch GLSL source into
// separate vertex/fragment files (split first done in Atomic, reused here);
// `#version 130` replaced with `#version 300 es`; RetroArch `#pragma
// parameter` lines removed (the in-code defaults are the shipped values).
// Shader bodies are otherwise unchanged from the MIT-licensed original
// (see licence header below).
/*
    FSR - [RCAS] ROBUST CONTRAST ADAPTIVE SHARPENING
    Ported from https://www.shadertoy.com/view/stXSWB, MIT license
*/


#if __VERSION__ >= 130
#define COMPAT_VARYING out
#define COMPAT_ATTRIBUTE in
#define COMPAT_TEXTURE texture
#else
#define COMPAT_VARYING varying 
#define COMPAT_ATTRIBUTE attribute 
#define COMPAT_TEXTURE texture2D
#endif

#ifdef GL_ES
#define COMPAT_PRECISION mediump
#else
#define COMPAT_PRECISION
#endif

COMPAT_ATTRIBUTE vec4 VertexCoord;
COMPAT_ATTRIBUTE vec4 COLOR;
COMPAT_ATTRIBUTE vec4 TexCoord;
COMPAT_VARYING vec4 COL0;
COMPAT_VARYING vec4 TEX0;

uniform mat4 MVPMatrix;
uniform COMPAT_PRECISION int FrameDirection;
uniform COMPAT_PRECISION int FrameCount;
uniform COMPAT_PRECISION vec2 OutputSize;
uniform COMPAT_PRECISION vec2 TextureSize;
uniform COMPAT_PRECISION vec2 InputSize;

void main()
{
    gl_Position = MVPMatrix * VertexCoord;
    COL0 = COLOR;
    TEX0.xy = TexCoord.xy;
}
