#include "/lib/settings.glsl"
#include "/lib/lighting.glsl"
varying vec4 tint;
#ifdef VSH
void main() { gl_Position = ftransform(); tint = gl_Color; }
#endif
#ifdef FSH
uniform mat4 gbufferProjectionInverse, gbufferModelViewInverse;
uniform float viewWidth, viewHeight, frameTimeCounter;
uniform vec3 cameraPosition, skyColor;
uniform int renderStage;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 cell = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(cell), hash(cell + vec2(1,0)), f.x),
               mix(hash(cell + vec2(0,1)), hash(cell + vec2(1,1)), f.x), f.y);
}
void main() {
    /* DRAWBUFFERS:0 */
    if (renderStage == MC_RENDER_STAGE_STARS) {
        gl_FragData[0] = vec4(tint.rgb * 0.65, tint.a);
        return;
    }
    if (renderStage == MC_RENDER_STAGE_SUNSET) discard;
    vec2 screen = gl_FragCoord.xy / vec2(viewWidth, viewHeight) * 2.0 - 1.0;
    vec4 v = gbufferProjectionInverse * vec4(screen, 1.0, 1.0);
    vec3 ray = normalize(mat3(gbufferModelViewInverse) * v.xyz);
    vec3 sky = mix(fogColor * fogColor, skyColor * skyColor * vec3(0.82, 0.94, 1.06),
        smoothstep(-0.06, 0.65, ray.y));
    #ifdef OVERWORLD
    if (ray.y > 0.025 && isEyeInWater == 0) {
        vec2 cloudUv = (ray.xz / max(ray.y, 0.025) * max(160.0 - cameraPosition.y, 25.0)
            + cameraPosition.xz) * 0.0025 + vec2(frameTimeCounter * 0.003, 0.0);
        float clouds = noise(cloudUv) * 0.70 + noise(cloudUv * 2.1) * 0.30;
        float coverage = smoothstep(0.53 - rainStrength * 0.12, 0.72, clouds)
            * smoothstep(0.025, 0.16, ray.y);
        vec3 cloudColor = mix(vec3(0.10,0.13,0.20), vec3(1.05,1.00,0.90), solDay);
        cloudColor = mix(cloudColor, vec3(0.32), rainStrength * 0.55);
        sky = mix(sky, cloudColor * (0.78 + 0.22 * clouds), coverage * 0.9);
    }
    #endif
    gl_FragData[0] = vec4(sky, 1.0);
}
#endif
