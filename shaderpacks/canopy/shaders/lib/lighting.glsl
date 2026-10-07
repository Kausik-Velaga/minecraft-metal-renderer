uniform float canDay, canDusk, canWarm, canCold, canPale, canHumid;
uniform float rainStrength, far;
uniform vec3 fogColor, skyColor, cameraPosition, cameraPositionFract;
uniform vec3 shadowLightPosition;
uniform mat4 gbufferModelViewInverse;
uniform int isEyeInWater;

float fifth(float x) { float x2 = x * x; return x2 * x2 * x; }
vec3 lightDirection() { return normalize(mat3(gbufferModelViewInverse) * shadowLightPosition); }
vec3 sunlight() {
    #ifdef OVERWORLD
    vec3 day = mix(vec3(2.35, 2.18, 1.82), vec3(2.70, 1.45, 0.67), canDusk);
    day *= mix(vec3(1.0), vec3(1.06, 0.97, 0.88), canWarm);
    day = mix(day, vec3(0.80, 0.90, 0.93), canPale * 0.8);
    return mix(vec3(0.13, 0.19, 0.31), day, canDay) * (1.0 - rainStrength * 0.72);
    #elif defined END
    return vec3(0.42, 0.38, 0.54);
    #else
    return vec3(0.0);
    #endif
}
vec3 ambientTint() {
    #ifdef NETHER
    return vec3(0.24, 0.12, 0.075);
    #elif defined END
    return vec3(0.28, 0.25, 0.40);
    #else
    vec3 day = mix(vec3(0.26, 0.38, 0.56), vec3(0.25, 0.35, 0.64), canCold);
    day = mix(day, vec3(0.28, 0.31, 0.30), canPale);
    return mix(vec3(0.036, 0.065, 0.12), day, canDay);
    #endif
}
vec3 skyRadiance(vec3 ray) {
    #ifdef OVERWORLD
    float horizon = 1.0 - smoothstep(0.0, 0.60, abs(ray.y));
    float forward = pow(max(dot(ray, lightDirection()), 0.0), 6.0);
    vec3 zenith = skyColor * skyColor * vec3(0.78, 0.94, 1.16) * 1.6;
    vec3 low = mix(fogColor * fogColor * 1.4, vec3(0.80, 0.30, 0.16), canDusk * canDay * 0.72);
    low += vec3(0.55, 0.30, 0.11) * canDay * forward * (0.25 + canDusk);
    vec3 sky = mix(zenith, low, horizon);
    sky = mix(sky, vec3(0.17, 0.19, 0.19), canPale * 0.82);
    return mix(sky, fogColor * fogColor, rainStrength * 0.60);
    #else
    return fogColor * fogColor;
    #endif
}
vec3 distanceFog(vec3 color, vec3 relative, float skyLight) {
    float d = length(relative);
    if (isEyeInWater == 1) {
        vec3 extinction = exp2(-d * vec3(0.16, 0.055, 0.035));
        return color * extinction + vec3(0.018, 0.12, 0.17) * (1.0 - extinction);
    }
    float density = 0.0007 + canHumid * 0.0012 + canPale * 0.014 + rainStrength * 0.008;
    float height = exp2(-max(cameraPosition.y + relative.y * 0.5 - 64.0, 0.0) * 0.018);
    float amount = (1.0 - exp2(-d * density * height)) * clamp(skyLight * 1.5, 0.0, 1.0);
    amount = max(amount, smoothstep(far * 0.55, far * 0.98, d));
    vec3 fog = skyRadiance(normalize(relative + vec3(0.00001)));
    #ifdef NETHER
    amount = max(amount, 1.0 - exp2(-d * 0.012));
    #endif
    if (isEyeInWater > 1) { amount = 1.0 - exp2(-d * 0.8); fog = fogColor * fogColor; }
    return mix(color, fog, amount);
}
