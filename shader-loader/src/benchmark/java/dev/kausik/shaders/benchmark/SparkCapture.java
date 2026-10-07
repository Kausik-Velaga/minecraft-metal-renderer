package dev.kausik.shaders.benchmark;

import com.mojang.brigadier.CommandDispatcher;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

/** Optional benchmark-only adapter to the pinned external Spark Fabric mod; no upstream code. */
final class SparkCapture {
  private final Path directory = FabricLoader.getInstance().getConfigDir().resolve("spark");
  private Object platform, sender;
  private Method execute;
  private CompletableFuture<?> starting;
  private Set<Path> previousFiles;
  private String engine;

  boolean startWhenReady(Minecraft client) throws Exception {
    if (starting == null) {
      var mod = FabricLoader.getInstance().getModContainer("spark").orElseThrow();
      if (!mod.getMetadata().getVersion().getFriendlyString().equals("1.10.187"))
        throw new IllegalStateException("Spark adapter requires pinned version 1.10.187");
      // Use the client command owner, not SparkProvider: its singleton can refer to the server.
      var dispatcher = (CommandDispatcher<?>) Class.forName(
              "net.fabricmc.fabric.api.client.command.v2.ClientCommands")
          .getMethod("getActiveDispatcher").invoke(null);
      if (dispatcher == null || dispatcher.getRoot().getChild("sparkc") == null)
        throw new IllegalStateException("Spark client commands are unavailable");
      var plugin = dispatcher.getRoot().getChild("sparkc").getCommand();
      var field = Class.forName("me.lucko.spark.minecraft.plugin.MinecraftSparkPlugin")
          .getDeclaredField("platform");
      field.setAccessible(true);
      platform = field.get(plugin);
      sender = Class.forName("me.lucko.spark.fabric.FabricClientCommandSender")
          .getConstructor(ClientSuggestionProvider.class)
          .newInstance(client.getConnection().getSuggestionsProvider());
      execute = platform.getClass().getMethod("executeCommand",
          Class.forName("me.lucko.spark.common.command.sender.CommandSender"), String[].class);
      previousFiles = profiles();
      starting = command("profiler", "start", "--thread", "*", "--not-combined", "--interval", "4");
      return false;
    }
    if (!starting.isDone()) return false;
    starting.join();
    var container = platform.getClass().getMethod("getSamplerContainer").invoke(platform);
    var sampler = container.getClass().getMethod("getActiveSampler").invoke(container);
    if (sampler == null) throw new IllegalStateException("Spark did not start a sampler");
    engine = sampler.getClass().getSimpleName();
    if (!engine.equals("AsyncSampler"))
      throw new IllegalStateException("Expected Spark async-profiler, got " + engine);
    return true;
  }

  CompletableFuture<Map<String, Object>> finish(Path destination) throws Exception {
    return command("profiler", "stop", "--save-to-file").thenApply(unused -> {
      try {
        var files = profiles();
        files.removeAll(previousFiles);
        if (files.size() != 1) throw new IllegalStateException("Expected one new Spark profile, got " + files);
        byte[] bytes = Files.readAllBytes(files.iterator().next());
        var data = Class.forName("me.lucko.spark.proto.SparkSamplerProtos$SamplerData")
            .getMethod("parseFrom", byte[].class).invoke(null, (Object) bytes);
        int threads = (int) data.getClass().getMethod("getThreadsCount").invoke(data);
        if (threads == 0) throw new IllegalStateException("Spark profile contains no thread samples");
        Files.createDirectories(destination.getParent());
        Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", destination.toAbsolutePath().toString());
        result.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        result.put("version", "1.10.187");
        result.put("engine", engine);
        result.put("event", "wall");
        result.put("intervalMillis", 4);
        result.put("threadCount", threads);
        result.put("uploaded", false);
        result.put("notes", "All threads, grouped by individual name. Wall samples include waits;"
            + " not CPU execution percentages or GPU timings. Capture surrounds the measured interval."
            + " Startup and export are outside frame statistics.");
        return result;
      } catch (Exception failure) {
        throw new java.util.concurrent.CompletionException(failure);
      }
    });
  }

  void cancel() {
    if (execute == null) return;
    try {
      command("profiler", "cancel");
    } catch (Exception failure) {
      System.err.println("[Shader benchmark] Spark cancellation failed: " + failure);
    }
  }

  private CompletableFuture<?> command(String... arguments) throws Exception {
    return ((CompletableFuture<?>) execute.invoke(platform, sender, arguments))
        .orTimeout(30, TimeUnit.SECONDS);
  }

  private Set<Path> profiles() throws Exception {
    Files.createDirectories(directory);
    try (var files = Files.list(directory)) {
      return files.filter(p -> p.getFileName().toString().endsWith(".sparkprofile"))
          .collect(Collectors.toSet());
    }
  }
}
