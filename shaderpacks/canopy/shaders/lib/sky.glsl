#include "/lib/settings.glsl"
#include "/lib/lighting.glsl"
varying vec4 tint;
#ifdef VSH
void main(){gl_Position=ftransform();tint=gl_Color;}
#endif
#ifdef FSH
uniform mat4 gbufferProjectionInverse;
uniform float viewWidth,viewHeight;
uniform int renderStage;
void main(){
    /* DRAWBUFFERS:0 */
    if(renderStage==MC_RENDER_STAGE_STARS){gl_FragData[0]=vec4(tint.rgb*0.75,tint.a);return;}
    if(renderStage==MC_RENDER_STAGE_SUNSET)discard;
    vec2 screen=gl_FragCoord.xy/vec2(viewWidth,viewHeight)*2.0-1.0;
    vec4 v=gbufferProjectionInverse*vec4(screen,1.0,1.0);
    vec3 ray=normalize(mat3(gbufferModelViewInverse)*v.xyz);
    gl_FragData[0]=vec4(skyRadiance(ray),1.0);
}
#endif
