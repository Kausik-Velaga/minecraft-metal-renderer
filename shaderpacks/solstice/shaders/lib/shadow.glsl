#include "/lib/settings.glsl"
varying vec2 uv;
varying float alpha;
#ifdef VSH
void main() {
    gl_Position = ftransform();
    uv = gl_MultiTexCoord0.xy;
    alpha = gl_Color.a;
}
#endif
#ifdef FSH
uniform sampler2D texture;
void main() {
    if (texture2D(texture, uv).a * alpha < 0.5) discard;
    // Depth only: no unused color slot (the frontend needs an actual attachment for dimensions).
}
#endif
