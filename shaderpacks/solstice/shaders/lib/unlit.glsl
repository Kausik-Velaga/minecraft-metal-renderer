#include "/lib/settings.glsl"
varying vec2 uv;
varying vec4 tint;
#ifdef VSH
void main() {
    gl_Position = ftransform();
    uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    tint = gl_Color;
}
#endif
#ifdef FSH
uniform sampler2D texture;
void main() {
    vec4 color = tint;
    #ifdef TEXTURED
    color *= texture2D(texture, uv);
    #endif
    /* DRAWBUFFERS:0 */
    gl_FragData[0] = vec4(color.rgb * color.rgb, color.a);
}
#endif
