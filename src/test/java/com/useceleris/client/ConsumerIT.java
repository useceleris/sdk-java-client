package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An application compiles and runs against the built jar, on the module path and the class path.
 */
class ConsumerIT {
  private static final String PROGRAM =
      """
      package consumer;

      import com.useceleris.client.CelerisClient;
      import com.useceleris.client.ClientOptions;
      import java.util.concurrent.CompletableFuture;

      public final class Main {
        public static void main(String[] arguments) {
          CelerisClient client =
              CelerisClient.create(ClientOptions.builder(request -> new CompletableFuture<>()).build());
          System.out.println(client.channel("room-1").segment("chat").segmentId() + " ok");
        }
      }
      """;

  @TempDir Path directory;

  @Test
  void runsOnTheModulePath() throws Exception {
    Path sources = directory.resolve("sources");
    Files.createDirectories(sources.resolve("consumer"));
    Files.writeString(sources.resolve("consumer/Main.java"), PROGRAM);
    Files.writeString(
        sources.resolve("module-info.java"), "module consumer { requires com.useceleris.client; }");
    Path output = directory.resolve("modules/consumer");
    String jar = System.getProperty("celeris.jar");

    compile(
        List.of(
            "--module-path",
            jar,
            "-d",
            output.toString(),
            sources.resolve("module-info.java").toString(),
            sources.resolve("consumer/Main.java").toString()));

    assertEquals(
        "chat ok",
        run(
            List.of(
                "--module-path",
                jar + java.io.File.pathSeparator + output,
                "-m",
                "consumer/consumer.Main")));
  } // end method runsOnTheModulePath

  @Test
  void runsOnTheClassPath() throws Exception {
    Path sources = directory.resolve("sources");
    Files.createDirectories(sources.resolve("consumer"));
    Files.writeString(sources.resolve("consumer/Main.java"), PROGRAM);
    Path output = directory.resolve("classes");
    String jar = System.getProperty("celeris.jar");

    compile(
        List.of(
            "-cp", jar, "-d", output.toString(), sources.resolve("consumer/Main.java").toString()));

    assertEquals(
        "chat ok", run(List.of("-cp", jar + java.io.File.pathSeparator + output, "consumer.Main")));
  } // end method runsOnTheClassPath

  private static void compile(List<String> arguments) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    List<String> all = new ArrayList<>(List.of("--release", "17"));
    all.addAll(arguments);

    assertEquals(
        0, compiler.run(null, null, null, all.toArray(String[]::new)), "compilation failed");
  } // end method compile

  private static String run(List<String> arguments) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(arguments);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the consumer did not exit");
    String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, process.exitValue(), output);

    return output;
  } // end method run
} // end class ConsumerIT
