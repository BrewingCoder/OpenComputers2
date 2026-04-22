#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float ScreenPxRange;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// MSDF rendering: each glyph's atlas pixel encodes 3 signed distance contributions
// (one per RGB channel) computed by msdf-atlas-gen. The final distance at any sample
// point is the median of the three — this preserves sharp corners that single-channel
// SDF blurs out.
//
// ScreenPxRange = atlas distanceRange * (renderedGlyphSize / atlasGlyphSize). It tells
// the shader how many on-screen pixels the [-0.5..+0.5] distance interval spans, so the
// edge smoothstep stays a 1-pixel transition regardless of GUI scale.
float median(vec3 c) {
    return max(min(c.r, c.g), min(max(c.r, c.g), c.b));
}

void main() {
    vec3 msd = texture(Sampler0, texCoord0).rgb;
    float sd = median(msd);
    float screenPxDistance = ScreenPxRange * (sd - 0.5);
    float opacity = clamp(screenPxDistance + 0.5, 0.0, 1.0);
    if (opacity == 0.0) discard;
    vec4 color = vec4(vertexColor.rgb, vertexColor.a * opacity) * ColorModulator;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
