#include "/lib/settings.glsl"
varying vec2 uv;
#ifdef VSH
void main(){gl_Position=ftransform();uv=gl_MultiTexCoord0.xy;}
#endif
#ifdef FSH
uniform sampler2D colortex0;
uniform float canDay,canPale;
#ifdef BLOOM
const bool colortex0MipmapEnabled = true;
#endif
void main(){
    vec3 color=texture2D(colortex0,uv).rgb;
    #ifdef BLOOM
    vec3 glow=texture2DLod(colortex0,uv,3.0).rgb*0.6+texture2DLod(colortex0,uv,5.0).rgb*0.4;
    color+=max(glow-vec3(1.15),vec3(0.0))*0.10;
    #endif
    float luminance=dot(color,vec3(0.2126,0.7152,0.0722));
    color=mix(color,vec3(luminance),canPale*0.38);
    // One common scale for RGB preserves hue as bright material values roll toward display white.
    float peak=max(max(color.r,color.g),color.b);
    float exposure=mix(1.38,1.18,canDay);
    color*= (1.0-exp2(-peak*exposure*1.442695))/max(peak,0.00001);
    color=pow(clamp(color,0.0,1.0),vec3(1.0/2.2));
    gl_FragColor=vec4(color,1.0);
}
#endif
