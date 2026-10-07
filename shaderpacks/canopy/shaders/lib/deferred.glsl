// Preserve opaque radiance for water transmission and reflection without attachment feedback.
varying vec2 uv;
#ifdef VSH
void main(){gl_Position=ftransform();uv=gl_MultiTexCoord0.xy;}
#endif
#ifdef FSH
uniform sampler2D colortex0;
void main(){
    /* DRAWBUFFERS:1 */
    gl_FragData[0]=texture2D(colortex0,uv);
}
#endif
