package dev.kausik.shaders.compile;

import dev.kausik.shaders.pack.AlphaTestPolicy;
import dev.kausik.shaders.pack.ShaderPack;
import dev.kausik.shaders.runtime.PackEnvironment;
import dev.kausik.shaders.runtime.ShadowCullingEligibility;
import java.nio.file.Path;
import java.util.Map;

/**
 * Proves the accepted alpha component stays untouched and unsupported programs remain unchanged.
 */
public final class EarlyAlphaDemotionTest {
  private static final String BODY =
      "void main(){vec4 pigment=texture2D(atlas,uv);"
          + "pigment.rgb*=2.0; light(pigment.xyz); sl_FragData0=pigment;}";
  private static final String VERTEX =
      "#version 120\nvarying vec2 uv;"
          + "void main(){gl_Position=gl_Vertex;uv=gl_Vertex.xy*0.5+0.5;}";
  private static final String FRAGMENT =
      "#version 120\nvarying vec2 uv;uniform sampler2D atlas;"
          + "void light(inout vec3 rgb){rgb+=vec3(dFdx(uv.x));}\n"
          + BODY.replace("sl_FragData0", "gl_FragColor");

  public static void main(String[] args) throws Exception {
    var greater = AlphaTestPolicy.greater(.1f);
    var valid = EarlyAlphaDemotion.apply(BODY, greater);
    if (!valid.applied()) throw new AssertionError(valid.reason());
    if (valid.source().indexOf("demote;") > valid.source().indexOf("pigment.rgb*="))
      throw new AssertionError("Demotion moved beyond lighting");
    for (String rejected :
        new String[] {
          BODY.replace("pigment", "alphaTestRef"),
          BODY.replace("pigment.rgb*=2.0;", "pigment.a=1.0;"),
          BODY.replace("pigment.rgb*=2.0;", "pigment.w*=0.5;"),
          BODY.replace("pigment.rgb*=2.0;", "pigment=vec4(1.0);"),
          BODY.replace("pigment.rgb*=2.0;", "pigment[component]=1.0;"),
          BODY.replace("pigment.rgb*=2.0;", "mutate(pigment);"),
          BODY.replace("pigment.rgb*=2.0;", "mutateAlpha(pigment.a);"),
          BODY.replace("pigment.rgb*=2.0;", "if(uv.x<0.5)return;"),
          BODY.replace("pigment.rgb*=2.0;", "if(uv.x<0.5)discard;"),
          BODY.replace("pigment.rgb*=2.0;", "if(uv.x<0.5)demote;"),
          BODY.replace("pigment.rgb*=2.0;", "gl_FragDepth=0.4;"),
          BODY.replace("pigment.rgb*=2.0;", "gl_SampleMask[0]=1;"),
          BODY.replace("pigment.rgb*=2.0;", "pigment.rgb+=float(gl_HelperInvocation);"),
          BODY.replace("pigment.rgb*=2.0;", "pigment.rgb+=float(subgroupAny(true));"),
          BODY.replace("pigment.rgb*=2.0;", "pigment.rgb+=float(anyInvocationARB(true));"),
          BODY.replace("pigment.rgb*=2.0;", "pigment.rgb+=float(ballotARB(true));"),
          BODY.replace("pigment.rgb*=2.0;", "pigment.rgb+=float(shuffleNV(1,0,32));"),
          BODY.replace("pigment.rgb*=2.0;", "pigment.rgb+=float(helperInvocationEXT());"),
          BODY.replace("pigment.rgb*=2.0;", "pigment.rgb+=float(clockARB());"),
          BODY.replace("sl_FragData0=pigment;", "sl_FragData0=vec4(pigment.rgb,1.0);"),
          BODY.replace("sl_FragData0=pigment;", "sl_FragData0=pigment;sl_FragData0.a=1.0;"),
          BODY.replace("sl_FragData0=pigment;", "sl_FragData1=vec4(1);sl_FragData0=pigment;"),
          BODY.replace("vec4 pigment=", "float first=0.0;vec4 pigment="),
          "void sideEffect(){imageStore(storage,ivec2(0),vec4(1));}" + BODY,
          "void sideEffect(){atomicAdd(counter,1);}" + BODY,
          "void sideEffect(){memoryBarrierImage();}" + BODY,
          "void sideEffect(){beginInvocationInterlockARB();}" + BODY
        }) {
      var result = EarlyAlphaDemotion.apply(rejected, greater);
      if (result.applied() || !result.source().equals(rejected))
        throw new AssertionError("Unsafe shader was modified: " + rejected);
    }
    // Comments cannot hide unsafe syntax or falsely reject a side-effect-free shader.
    if (!EarlyAlphaDemotion.apply("// imageStore and discard are only words\n" + BODY, greater)
        .applied()) throw new AssertionError("Comment affected proof");
    try (var compiler = new ShaderCompatibilityCompiler()) {
      for (var function : AlphaTestPolicy.Function.values()) {
        var policy = new AlphaTestPolicy(function, .5f);
        var normal =
            compiler.translate(
                VERTEX,
                FRAGMENT,
                "alpha_late",
                (ShaderCompatibilityCompiler.VertexAdapter) null,
                policy);
        var early =
            compiler.translate(
                VERTEX,
                FRAGMENT,
                "alpha_early",
                (ShaderCompatibilityCompiler.VertexAdapter) null,
                policy,
                true);
        if (early.fragmentSource().contains("demote;") != policy.enabled())
          throw new AssertionError("Wrong demotion for " + function);
        if (policy.enabled()) {
          if (!early.fragmentSource().contains(policy.passCondition("pigment.a", "alphaTestRef"))
              || !early
                  .fragmentSource()
                  .contains(policy.passCondition("sl_FragData0.a", "alphaTestRef"))
              || !early.fragmentSource().contains("sl_pack_fragment_main();"))
            throw new AssertionError("Early or final alpha semantics changed: " + function);
          if (!early
              .fragmentSource()
              .contains("#extension GL_EXT_demote_to_helper_invocation : require"))
            throw new AssertionError("Missing derivative-preserving extension");
        }
        if (!normal.preprocessedFragment().equals(early.preprocessedFragment()))
          throw new AssertionError("Pack source metadata changed");
      }
      if (args.length > 0) {
        ShaderPack pack = ShaderPack.load(Path.of(args[0]));
        if (!ShadowCullingEligibility.assess(
                pack, Map.of(), "minecraft:overworld", PackEnvironment.definitions())
            .eligible())
          throw new AssertionError("Supplied pack does not satisfy audited eligibility");
        var real =
            compiler.translate(
                pack.source("world0/gbuffers_terrain.vsh", Map.of(), PackEnvironment.definitions()),
                pack.source("world0/gbuffers_terrain.fsh", Map.of(), PackEnvironment.definitions()),
                "actual_bsl_terrain",
                (ShaderCompatibilityCompiler.VertexAdapter) null,
                greater,
                true);
        if (!real.fragmentSource().contains("demote;"))
          throw new AssertionError("Audited default BSL terrain did not prove stable alpha");
      }
    }
    System.out.println(
        "PASS: stable alpha component, unsafe-program fallback, all eight host policies, final-test"
            + " preservation"
            + (args.length > 0 ? ", supplied BSL terrain" : ""));
  }
}
