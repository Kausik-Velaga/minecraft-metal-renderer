#include "/lib/settings.glsl"
#include "/lib/lighting.glsl"
uniform mat4 gbufferModelViewInverse;
uniform mat4 shadowModelView, shadowProjection;
uniform vec3 shadowLightPosition;
uniform int renderStage;
varying vec2 uv;
varying vec4 tint;
varying vec3 worldRelative;
varying vec3 ambientLight;
varying vec3 directLight;
varying vec3 shadowCoord;
varying float skyLight;
#ifdef WATER
#ifdef VSH
attribute vec4 mc_Entity;
#endif
varying float waterMaterial;
varying vec3 worldNormal;
#endif

#ifdef VSH
void main() {
    vec4 view = gl_ModelViewMatrix * gl_Vertex;
    gl_Position = gl_ProjectionMatrix * view;
    worldRelative = (gbufferModelViewInverse * view).xyz;
    vec3 normal = normalize(gl_NormalMatrix * gl_Normal);
    vec3 worldN = normalize(mat3(gbufferModelViewInverse) * normal);
    uv = gl_MultiTexCoord0.xy;
    tint = gl_Color;
    vec2 light = clamp(gl_MultiTexCoord1.xy / 240.0, 0.0, 1.0);
    skyLight = light.y;
    float hemisphere = 0.72 + 0.28 * max(worldN.y, 0.0);
    float lambert = max(dot(normal, shadowLightPosition * 0.01), 0.0);
    ambientLight = ambientTint() * (light.y * light.y) * hemisphere
        + vec3(1.0, 0.57, 0.25) * (light.x * light.x * light.x) * 0.85 + vec3(0.022);
    directLight = sunTint() * lambert * smoothstep(0.70, 1.0, light.y);
    shadowCoord = (shadowProjection * shadowModelView * vec4(worldRelative + worldN * 0.035, 1.0)).xyz * 0.5 + 0.5;
    #ifdef WATER
    waterMaterial = float(abs(mc_Entity.x - 1.0) < 0.1);
    worldNormal = worldN;
    #endif
}
#endif

#ifdef FSH
uniform sampler2D texture;
#ifdef SHADOWS
uniform sampler2DShadow shadowtex0;
#endif
#ifdef WATER
uniform float frameTimeCounter;
uniform vec3 cameraPosition, skyColor;
uniform sampler2D depthtex1;
uniform mat4 gbufferProjectionInverse;
uniform float viewWidth, viewHeight;
#endif
float visibility() {
    #if defined SHADOWS && defined OVERWORLD
    vec3 p = shadowCoord;
    float edge = max(abs(p.x * 2.0 - 1.0), abs(p.y * 2.0 - 1.0));
    float fade = (1.0 - smoothstep(0.75, 0.98, edge))
        * (1.0 - smoothstep(shadowDistance * 0.65, shadowDistance * 0.95, length(worldRelative)));
    if (fade <= 0.0 || p.z <= 0.0 || p.z >= 1.0) return 1.0;
    p.z -= 0.00025;
    vec2 stepSize = vec2(0.55 / float(shadowMapResolution));
    float shadow = shadow2D(shadowtex0, p + vec3(stepSize, 0.0)).r
                 + shadow2D(shadowtex0, p - vec3(stepSize, 0.0)).r;
    return mix(1.0, shadow * 0.5, fade);
    #else
    return 1.0;
    #endif
}
void main() {
    vec4 albedo = texture2D(texture, uv);
    #ifdef TERRAIN
    albedo.rgb *= tint.rgb * tint.a;
    #else
    albedo *= tint;
    #endif
    // AO is already in the terrain vertex color; retain alpha for material blending.
    vec3 color = albedo.rgb * albedo.rgb * (ambientLight + directLight * visibility());
    #ifdef WATER
    if (waterMaterial > 0.5) {
        vec3 position = worldRelative + cameraPosition;
        float phase = frameTimeCounter * 0.65;
        vec3 n = normalize(worldNormal + vec3(sin(position.z * 0.75 + phase), 0.0,
            cos(position.x * 0.62 - phase)) * 0.035);
        float facing = clamp(dot(normalize(-worldRelative), n), 0.0, 1.0);
        float fresnel = 0.025 + 0.60 * pow(1.0 - facing, 5.0);
        float depth = texture2D(depthtex1, gl_FragCoord.xy / vec2(viewWidth, viewHeight)).r;
        vec4 behind = gbufferProjectionInverse * vec4(gl_FragCoord.xy / vec2(viewWidth, viewHeight) * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        float thickness = clamp(length(behind.xyz / behind.w) - length(worldRelative), 0.0, 24.0);
        color = mix(vec3(0.055, 0.23, 0.20), vec3(0.015, 0.10, 0.14), 1.0 - exp2(-thickness * 0.18))
            * (ambientLight + directLight * 0.35);
        color = mix(color, skyColor * skyColor * 0.8, fresnel);
        albedo.a = clamp(0.35 + thickness * 0.055 + fresnel * 0.3, 0.35, 0.94);
    }
    #endif
    color = distanceFog(color, worldRelative, skyLight);
    /* DRAWBUFFERS:0 */
    gl_FragData[0] = vec4(color, albedo.a);
}
#endif
