#include "/lib/settings.glsl"
#include "/lib/lighting.glsl"
#include "/lib/materials.glsl"
uniform mat4 shadowModelView, shadowProjection;
uniform int renderStage;
varying vec2 uv;
varying vec4 tint;
varying vec3 worldRelative, worldNormal, ambientLight, directLight, shadowCoord;
varying vec3 material;
varying float materialId, skyLight;
#ifdef VSH
attribute vec4 mc_Entity;
void main() {
    vec4 view=gl_ModelViewMatrix*gl_Vertex;
    gl_Position=gl_ProjectionMatrix*view;
    worldRelative=(gbufferModelViewInverse*view).xyz;
    worldNormal=normalize(mat3(gbufferModelViewInverse)*normalize(gl_NormalMatrix*gl_Normal));
    uv=gl_MultiTexCoord0.xy;
    tint=gl_Color;
    materialId=mc_Entity.x;
    material=materialProperties(materialId);
    vec2 light=clamp(gl_MultiTexCoord1.xy/240.0,0.0,1.0);
    skyLight=light.y;
    float skyExposure=light.y*light.y;
    #ifdef NETHER
    skyExposure=max(skyExposure,0.45);
    #endif
    float hemisphere=0.70+0.30*max(worldNormal.y,0.0);
    ambientLight=ambientTint()*skyExposure*hemisphere
        +vec3(1.0,0.53,0.20)*light.x*light.x*light.x*1.4+vec3(0.012,0.015,0.02);
    float nl=dot(worldNormal,lightDirection());
    float diffuse=max(nl,0.0);
    // Small wrapped/back-lit leaf term; not a thickness-resolved scattering simulation.
    diffuse=mix(diffuse,max((nl+0.35)/1.35,0.0)+max(-nl,0.0)*0.32,material.z);
    directLight=sunlight()*diffuse*smoothstep(0.55,1.0,light.y);
    shadowCoord=(shadowProjection*shadowModelView*vec4(worldRelative+worldNormal*0.035,1.0)).xyz*0.5+0.5;
}
#endif
#ifdef FSH
uniform sampler2D texture;
#ifdef MATERIAL_MAPS
uniform sampler2D materialtex;
#endif
#ifdef SHADOWS
#ifdef OVERWORLD
uniform sampler2DShadow shadowtex0;
#endif
#endif
#include "/lib/water.glsl"
float visibility() {
    #if defined SHADOWS && defined OVERWORLD
    if(renderStage==MC_RENDER_STAGE_HAND_SOLID || renderStage==MC_RENDER_STAGE_HAND_TRANSLUCENT)return 1.0;
    vec3 p=shadowCoord;
    #ifdef PIXEL_SHADOWS
    #ifdef TERRAIN
    // World-anchored 1/16-block cell centers; keep the original surface plane.
    vec3 cells=(floor((worldRelative+cameraPositionFract)*16.0)+0.5)*0.0625-cameraPositionFract;
    vec3 shift=cells-worldRelative;
    shift-=worldNormal*dot(shift,worldNormal);
    vec3 rotated=mat3(shadowModelView)*shift;
    p+=rotated*vec3(shadowProjection[0][0],shadowProjection[1][1],shadowProjection[2][2])*0.5;
    #endif
    #endif
    float edge=max(abs(p.x*2.0-1.0),abs(p.y*2.0-1.0));
    float fade=(1.0-smoothstep(0.80,0.98,edge))*(1.0-smoothstep(shadowDistance*0.72,shadowDistance*0.97,length(worldRelative)));
    if(fade<=0.0 || p.z<=0.0 || p.z>=1.0)return 1.0;
    p.z-=0.00022;
    vec2 stepSize=vec2(0.42/float(shadowMapResolution));
    float s=(shadow2D(shadowtex0,p+vec3(stepSize,0)).r+shadow2D(shadowtex0,p-vec3(stepSize,0)).r)*0.5;
    return mix(1.0,s,fade);
    #else
    return 1.0;
    #endif
}
void main() {
    vec4 texel=texture2D(texture,uv);
    vec4 albedo=texel;
    float ao=1.0;
    #ifdef TERRAIN
    albedo.rgb*=tint.rgb;
    ao=tint.a;
    #else
    albedo*=tint;
    #endif
    vec3 base=albedo.rgb*albedo.rgb;
    vec3 surfaceMaterial=material;
    float emission=-1.0;
    vec3 direct=directLight;
    #ifdef MATERIAL_MAPS
    vec4 mers=texture2D(materialtex,uv);
    // Zero roughness marks unannotated sprites and textures outside the block atlas.
    if(mers.b>0.0) {
        surfaceMaterial=vec3(mers.b,mers.r,mers.a);
        emission=mers.g;
        float nl=dot(normalize(worldNormal),lightDirection());
        float diffuse=mix(max(nl,0.0),max((nl+0.35)/1.35,0.0)+max(-nl,0.0)*0.32,mers.a);
        direct=sunlight()*diffuse*smoothstep(0.55,1.0,skyLight);
    }
    #endif
    float sunVisibility=visibility();
    vec3 color=base*(ambientLight*ao+direct*sunVisibility*mix(0.85,1.0,ao))*(1.0-surfaceMaterial.y*0.85);
    #ifdef MATERIAL_HIGHLIGHTS
    if(surfaceMaterial.x<0.99) {
        vec3 n=normalize(worldNormal),v=normalize(-worldRelative);
        float rough=clamp(surfaceMaterial.x,0.16,1.0);
        color+=specularLight(n,v,lightDirection(),base,rough,surfaceMaterial.y)*sunlight()*sunVisibility*skyLight;
        vec3 f0=mix(vec3(0.04),base,surfaceMaterial.y);
        vec3 fresnel=f0+(1.0-f0)*fifth(1.0-max(dot(n,v),0.0));
        color+=skyRadiance(reflect(-v,n))*fresnel*(1.0-rough*0.70)*skyLight*ao;
    }
    #endif
    if(emission>=0.0)color+=base*emission*3.0;
    else if(materialId>=7.0 && materialId<=9.0) {
        float mask=smoothstep(0.35,0.85,max(texel.r,max(texel.g,texel.b)));
        if(materialId==8.0)mask*=smoothstep(0.02,0.30,texel.b-texel.r);
        color+=base*mask*(materialId==9.0?3.5:2.5);
    }
    #ifdef CAUSTICS
    if(isEyeInWater==1)color*=1.0+causticPattern(worldRelative+cameraPosition)*0.45
        *skyLight*canDay*max(worldNormal.y,0.0);
    #endif
    #ifdef WATER
    if(abs(materialId-1.0)<0.1 && isEyeInWater==0) {
        color=waterColor(worldRelative,normalize(worldNormal),lightDirection(),sunVisibility,tint.rgb);
        // Transmission is already composed from a distinct opaque snapshot; don't blend twice.
        gl_FragData[0]=vec4(distanceFog(color,worldRelative,skyLight),1.0);
        return;
    }
    #endif
    /* DRAWBUFFERS:0 */
    gl_FragData[0]=vec4(distanceFog(color,worldRelative,skyLight),albedo.a);
}
#endif
