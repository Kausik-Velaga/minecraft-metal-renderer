package dev.kausik.shaders.runtime;

import dev.kausik.shaders.pack.ShaderPack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** CPU checks for pack identity, immutable activation and fail-closed receiver eligibility. */
public final class ShadowCullingEligibilityTest {
  public static void main(String[] args) throws Exception {
    Path temporary = Files.createTempDirectory("shadow-culling-eligibility-");
    try {
      verifySnapshotIdentity(temporary);
      verifyEligibilityRules();
      if (args.length > 0) verifySuppliedPack(Path.of(args[0]));
      System.out.println(
          "PASS: canonical snapshot identity, archive/directory equivalence, immutable activation,"
              + " content/options/dimension/environment culling gates"
              + (args.length > 0 ? ", supplied BSL snapshot" : " (supplied pack not requested)"));
    } finally {
      try (var paths = Files.walk(temporary)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private static void verifySnapshotIdentity(Path temporary) throws Exception {
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put(
        "world0/gbuffers_terrain.vsh",
        utf8("#version 120\n#define QUALITY 1 //[1 2]\nvoid main() { gl_Position = gl_Vertex; }\n"));
    files.put(
        "world0/gbuffers_terrain.fsh",
        utf8("#version 120\nvoid main() { gl_FragColor = vec4(1); }\n"));
    files.put("shaders.properties", utf8("screen=QUALITY\n"));
    files.put("textures/sample.bin", new byte[] {0, 1, (byte) 255, 0, 9});
    Path directory = temporary.resolve("directory");
    writeDirectory(directory, files);
    Path namedZip = temporary.resolve("BSL_v10.1.8.zip");
    writeZip(namedZip, files, "shaders/", false, 0);
    Path repack = temporary.resolve("renamed.zip");
    writeZip(repack, files, "unrelated-container/shaders/", true, 9);
    ShaderPack loaded = ShaderPack.load(namedZip);
    String identity = loaded.contentFingerprint();
    // Independently generated fixed vector verifies domain, lengths, order and raw binary bytes.
    equal("4007e2b96a99db1a90cdd0c61961bd48befcdee1336569765949b77ee4e7f05a", identity);
    equal(identity, ShaderPack.load(repack).contentFingerprint());
    equal(identity, ShaderPack.load(directory).contentFingerprint());
    denied(
        ShadowCullingEligibility.assess(loaded, Map.of(), "minecraft:overworld", environment()),
        "unrecognized-pack-content");

    byte[] returned = loaded.bytes("textures/sample.bin");
    returned[0] = 100;
    if (loaded.bytes("textures/sample.bin")[0] != 0)
      throw new AssertionError("Public bytes mutated the active snapshot");
    equal(identity, loaded.contentFingerprint());

    // Every captured file participates, including data that is not shader source.
    for (String path : files.keySet()) {
      Map<String, byte[]> changed = new LinkedHashMap<>(files);
      byte[] bytes = changed.get(path).clone();
      bytes[bytes.length - 1] ^= 1;
      changed.put(path, bytes);
      Path archive = temporary.resolve("changed.zip");
      writeZip(archive, changed, "shaders/", false, 6);
      different(identity, ShaderPack.load(archive).contentFingerprint(), "Changed " + path);
    }
    Map<String, byte[]> added = new LinkedHashMap<>(files);
    added.put("new-file.bin", new byte[0]);
    Path addedZip = temporary.resolve("added.zip");
    writeZip(addedZip, added, "shaders/", false, 6);
    different(identity, ShaderPack.load(addedZip).contentFingerprint(), "Added file");

    // The file backing an activated pack can change or disappear without changing its identity
    // or contents. A fresh activation sees the new content, and cannot inherit the old digest.
    writeZip(namedZip, added, "shaders/", false, 6);
    different(identity, ShaderPack.load(namedZip).contentFingerprint(), "Reload after replacement");
    Files.delete(namedZip);
    equal(identity, loaded.contentFingerprint());
    if (loaded.contains("new-file.bin")) throw new AssertionError("Active snapshot reread disk");
    equal("screen=QUALITY\n", loaded.text("shaders.properties"));
  }

  private static void verifyEligibilityRules() {
    Map<String, String> defaults = Map.of("SHADOW", "true", "shadowDistance", "256.0");
    allowed(evaluate(defaults, defaults, "minecraft:overworld", "world0", environment()));
    // An explicit selection of every default is equivalent to omitting overrides.
    allowed(evaluate(new LinkedHashMap<>(defaults), defaults, "minecraft:overworld", "world0", environment()));
    denied(
        evaluate(Map.of("SHADOW", "true", "shadowDistance", "512.0"), defaults,
            "minecraft:overworld", "world0", environment()),
        "non-default-pack-options");
    for (String dimension : new String[] {"minecraft:the_nether", "minecraft:the_end", "example:overworld"})
      denied(evaluate(defaults, defaults, dimension, "world0", environment()), "unsupported-dimension");
    denied(evaluate(defaults, defaults, "minecraft:overworld", "", environment()), "unsupported-pack-directory");
    for (String mutation : new String[] {"version", "capability", "stage", "removed"}) {
      Map<String, String> changed = new LinkedHashMap<>(environment());
      switch (mutation) {
        case "version" -> changed.put("MC_VERSION", "260400");
        case "capability" -> changed.put("IS_IRIS", "");
        case "stage" -> changed.put("MC_RENDER_STAGE_ENTITIES", "99");
        case "removed" -> changed.remove("MC_OS_MAC");
        default -> throw new AssertionError(mutation);
      }
      denied(evaluate(defaults, defaults, "minecraft:overworld", "world0", changed), "unsupported-environment");
    }
  }

  private static void verifySuppliedPack(Path path) throws Exception {
    ShaderPack pack = ShaderPack.load(path);
    equal(ShadowCullingEligibility.BSL_10_1_8_FINGERPRINT, pack.contentFingerprint());
    allowed(ShadowCullingEligibility.assess(pack, Map.of(), "minecraft:overworld", environment()));
    allowed(ShadowCullingEligibility.assess(pack, pack.optionValues(Map.of()), "minecraft:overworld", environment()));
    denied(ShadowCullingEligibility.assess(pack, Map.of("shadowDistance", "512.0"),
        "minecraft:overworld", environment()), "non-default-pack-options");
    denied(ShadowCullingEligibility.assess(pack, Map.of(), "minecraft:the_end", environment()), "unsupported-dimension");
    denied(ShadowCullingEligibility.assess(pack, Map.of(), "example:overworld", environment()), "unsupported-dimension");
  }

  private static ShadowCullingEligibility.Result evaluate(
      Map<String, String> effective, Map<String, String> defaults, String dimension,
      String directory, Map<String, String> environment) {
    return ShadowCullingEligibility.evaluate(ShadowCullingEligibility.BSL_10_1_8_FINGERPRINT,
        effective, defaults, dimension, directory, environment);
  }

  private static Map<String, String> environment() {
    return PackEnvironment.definitions();
  }

  private static void allowed(ShadowCullingEligibility.Result result) {
    if (!result.eligible()) throw new AssertionError("Unexpected rejection: " + result.reason());
  }

  private static void denied(ShadowCullingEligibility.Result result, String reason) {
    if (result.eligible() || !result.reason().equals(reason))
      throw new AssertionError("Expected " + reason + ", got " + result);
  }

  private static void equal(String expected, String actual) {
    if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
  }

  private static void different(String first, String second, String message) {
    if (first.equals(second)) throw new AssertionError(message + " did not affect content identity");
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static void writeDirectory(Path directory, Map<String, byte[]> files) throws IOException {
    for (var entry : files.entrySet()) {
      Path target = directory.resolve("shaders").resolve(entry.getKey());
      Files.createDirectories(target.getParent());
      Files.write(target, entry.getValue());
    }
  }

  private static void writeZip(Path path, Map<String, byte[]> files, String prefix,
      boolean reverse, int compression) throws IOException {
    var names = new ArrayList<>(files.keySet());
    if (reverse) Collections.reverse(names);
    try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
      zip.setLevel(compression);
      for (String name : names) {
        ZipEntry entry = new ZipEntry(prefix + name);
        entry.setTime(reverse ? 2_000_000 : 1_000_000);
        zip.putNextEntry(entry);
        zip.write(files.get(name));
        zip.closeEntry();
      }
    }
  }
}
