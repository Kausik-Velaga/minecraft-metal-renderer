uniform float solDay, solDusk, rainStrength;
uniform vec3 fogColor;
uniform int isEyeInWater;
uniform float far;

vec3 sunTint() {
    return mix(vec3(0.14, 0.19, 0.32),
        mix(vec3(1.10, 1.01, 0.84), vec3(1.24, 0.69, 0.35), solDusk), solDay)
        * (1.0 - rainStrength * 0.65);
}
vec3 ambientTint() {
    return mix(vec3(0.045, 0.065, 0.11), vec3(0.31, 0.40, 0.50), solDay);
}
vec3 distanceFog(vec3 color, vec3 worldRelative, float skyLight) {
    float distanceToEye = length(worldRelative);
    float fog = smoothstep(far * 0.30, far * 0.98, distanceToEye);
    fog = max(fog, (1.0 - exp2(-distanceToEye * (0.0008 + rainStrength * 0.009)))
        * clamp(skyLight, 0.0, 1.0));
    if (isEyeInWater == 1) {
        return mix(color, vec3(0.025, 0.16, 0.20), 1.0 - exp2(-distanceToEye * 0.12));
    }
    if (isEyeInWater > 1) fog = 1.0 - exp2(-distanceToEye * 0.8);
    return mix(color, fogColor * fogColor, fog);
}
