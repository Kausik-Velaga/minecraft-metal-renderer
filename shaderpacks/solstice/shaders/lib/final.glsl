#include "/lib/settings.glsl"
varying vec2 uv;
#ifdef VSH
void main() { gl_Position = ftransform(); uv = gl_MultiTexCoord0.xy; }
#endif
#ifdef FSH
uniform sampler2D colortex0;
#ifdef BLOOM
const bool colortex0MipmapEnabled = true;
#endif
void main() {
    vec3 color = texture2D(colortex0, uv).rgb;
    #ifdef BLOOM
    // Broad glow uses reduced-resolution mip levels, with no temporal history.
    vec3 glow = texture2DLod(colortex0, uv, 3.0).rgb * 0.6
              + texture2DLod(colortex0, uv, 5.0).rgb * 0.4;
    color += max(glow - vec3(0.72), vec3(0.0)) * 0.10;
    #endif
    // Gentle highlight shoulder followed by display encoding; preserve pixel-art detail.
    color = color * (1.0 + color * 0.16) / (1.0 + color * 0.40);
    color = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2));
    gl_FragColor = vec4(color, 1.0);
}
#endif
