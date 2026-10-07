#include "/lib/settings.glsl"
#include "/lib/lighting.glsl"
varying vec3 cloudLight,relative;
varying float alpha;
#ifdef VSH
void main(){
    vec4 view=gl_ModelViewMatrix*gl_Vertex;
    gl_Position=gl_ProjectionMatrix*view;
    relative=(gbufferModelViewInverse*view).xyz;
    vec3 n=normalize(mat3(gbufferModelViewInverse)*normalize(gl_NormalMatrix*gl_Normal));
    float edge=pow(1.0-abs(n.y),2.0);
    cloudLight=mix(vec3(0.065,0.09,0.14),vec3(1.35,1.42,1.55),canDay);
    cloudLight+=sunlight()*(max(dot(n,lightDirection()),0.0)*0.34+edge*0.16);
    cloudLight=mix(cloudLight,vec3(0.21,0.24,0.29),rainStrength*0.8);
    cloudLight=mix(cloudLight,vec3(0.22,0.25,0.24),canPale*0.8);
    alpha=gl_Color.a;
}
#endif
#ifdef FSH
void main(){
    /* DRAWBUFFERS:0 */
    // Cloud geometry extends above and beyond terrain's draw distance. Applying
    // terrain's far-plane fade would erase the entire layer at ordinary settings.
    float haze=1.0-exp2(-length(relative)*(0.00035+rainStrength*0.0015+canPale*0.004));
    vec3 color=mix(cloudLight,skyRadiance(normalize(relative)),haze);
    gl_FragData[0]=vec4(color,alpha);
}
#endif
