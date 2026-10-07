// Analytic material response. No proprietary material textures are distributed.
vec3 materialProperties(float id) {
    // roughness, metalness, foliage transmission
    if (id == 2.0) return vec3(1.0, 0.0, 0.65);
    if (id == 3.0) return vec3(0.30, 0.90, 0.0);
    if (id == 4.0) return vec3(0.58, 0.0, 0.0);
    if (id == 5.0) return vec3(0.22, 0.0, 0.18);
    if (id == 6.0) return vec3(0.16, 0.0, 0.0);
    if (id == 10.0) return vec3(0.25, 0.12, 0.0);
    return vec3(1.0, 0.0, 0.0);
}
vec3 specularLight(vec3 n, vec3 v, vec3 l, vec3 base, float roughness, float metalness) {
    float nl = max(dot(n,l), 0.0), nv = max(dot(n,v), 0.001);
    vec3 h = normalize(v+l);
    float nh = max(dot(n,h), 0.0), vh = max(dot(v,h), 0.0);
    float a = max(roughness * roughness, 0.045), a2 = a*a;
    float denominator = nh*nh*(a2-1.0)+1.0;
    float distribution = a2 / max(3.14159265*denominator*denominator, 0.00001);
    float k = (roughness+1.0)*(roughness+1.0)*0.125;
    float geometry = nv/(nv*(1.0-k)+k) * nl/(nl*(1.0-k)+k);
    vec3 f0 = mix(vec3(0.04),base,metalness);
    vec3 fresnel = f0+(1.0-f0)*fifth(1.0-vh);
    return fresnel * distribution * geometry / max(4.0*nv, 0.001);
}
