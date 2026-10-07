uniform float frameTimeCounter;
float causticPattern(vec3 position) {
    vec2 p = (floor(position.xz * 16.0) + 0.5) * 0.0625;
    float t = frameTimeCounter * 0.65;
    float a = sin(p.x * 2.7 + sin(p.y * 2.1 + t) * 1.4 - t);
    float b = sin(p.y * 2.5 + sin(p.x * 2.3 - t) * 1.5 + t * 0.8);
    return pow(max(1.0 - abs(a + b) * 0.65, 0.0), 10.0);
}
#ifdef WATER
uniform sampler2D colortex1, depthtex1;
uniform mat4 gbufferProjection, gbufferProjectionInverse, gbufferModelView;
uniform float viewWidth, viewHeight;
vec3 viewPosition(vec2 coord, float depth) {
    vec4 p = gbufferProjectionInverse * vec4(coord*2.0-1.0,depth*2.0-1.0,1.0);
    return p.xyz / p.w;
}
vec3 reflectedScene(vec3 origin, vec3 direction, vec3 fallback) {
    #ifdef WATER_REFLECTIONS
    float previous = 0.15;
    for (int i = 0; i < REFLECTION_STEPS; i++) {
        float fraction = float(i+1) / float(REFLECTION_STEPS);
        float distance = 0.2 + fraction*fraction*40.0;
        vec3 point = origin + direction*distance;
        vec4 projected = gbufferProjection*vec4(point,1.0);
        if (projected.w <= 0.0) break;
        vec2 coord = projected.xy/projected.w*0.5+0.5;
        if (min(coord.x,coord.y)<0.005 || max(coord.x,coord.y)>0.995) break;
        float depth = texture2D(depthtex1,coord).r;
        float gap = viewPosition(coord,depth).z - point.z;
        if (depth < 0.99999 && gap > 0.0 && gap < 0.20+(distance-previous)*abs(direction.z)) {
            // Refine the first crossing without increasing every ray's step count.
            float lo=previous, hi=distance;
            for (int j=0;j<2;j++) {
                float mid=(lo+hi)*0.5;
                vec3 testPoint=origin+direction*mid;
                vec4 clip=gbufferProjection*vec4(testPoint,1.0);
                vec2 testUv=clip.xy/clip.w*0.5+0.5;
                float testGap=viewPosition(testUv,texture2D(depthtex1,testUv).r).z-testPoint.z;
                if(testGap>0.0)hi=mid;else lo=mid;
            }
            vec4 hit=gbufferProjection*vec4(origin+direction*hi,1.0);
            vec2 hitUv=hit.xy/hit.w*0.5+0.5;
            float edge=min(min(hitUv.x,hitUv.y),min(1.0-hitUv.x,1.0-hitUv.y));
            return mix(fallback,texture2D(colortex1,hitUv).rgb,smoothstep(0.01,0.12,edge));
        }
        previous=distance;
    }
    #endif
    return fallback;
}
vec3 waterColor(vec3 relative, vec3 normal, vec3 light, float visibility, vec3 biomeTint) {
    vec2 coord = gl_FragCoord.xy/vec2(viewWidth,viewHeight);
    float depth = texture2D(depthtex1,coord).r;
    vec3 behindView=viewPosition(coord,depth);
    vec3 behindWorld=(gbufferModelViewInverse*vec4(behindView,1.0)).xyz;
    float thickness=clamp(length(behindWorld-relative),0.0,40.0);
    vec3 transmitted=texture2D(colortex1,coord).rgb;
    #ifdef CAUSTICS
    // Only pixels behind a water surface receive this above-water projection.
    transmitted *= 1.0 + causticPattern(behindWorld+cameraPosition) * exp2(-thickness*0.16)
        * canDay * visibility * 0.52;
    #endif
    vec3 p=(floor((relative+cameraPosition)*16.0)+0.5)*0.0625;
    float time=frameTimeCounter*0.65;
    // Fade unresolved waves before they turn into a distant checkerboard. Derivatives
    // use continuous position, not the quantized cells, so adjacent pixels agree.
    float footprint=max(length(dFdx(relative.xz)),length(dFdy(relative.xz)));
    float waveScale=0.028*exp2(-footprint*2.0);
    vec3 n=normalize(normal+vec3(sin(p.z*2.1+time)+sin(p.x*1.4-p.z+time*0.8),0.0,
        cos(p.x*1.8-time)+sin(p.z*1.3+p.x-time*0.6))*waveScale);
    vec3 v=normalize(-relative);
    float nv=max(dot(n,v),0.0);
    float fresnel=0.025+0.975*fifth(1.0-nv);
    vec3 reflected=reflect(-v,n);
    vec3 sky=skyRadiance(reflected);
    vec3 origin=(gbufferModelView*vec4(relative+n*0.07,1.0)).xyz;
    vec3 reflection=reflectedScene(origin,mat3(gbufferModelView)*reflected,sky);
    vec3 extinction=exp2(-thickness*vec3(0.18,0.065,0.035));
    vec3 body=mix(vec3(0.018,0.095,0.12),biomeTint*biomeTint*0.24,0.30)
        * (0.30+canDay*0.70);
    vec3 color=mix(transmitted*extinction+body*(1.0-extinction),reflection,fresnel);
    color+=specularLight(n,v,light,vec3(0.02),0.16,0.0)*sunlight()*visibility;
    return color;
}
#endif
