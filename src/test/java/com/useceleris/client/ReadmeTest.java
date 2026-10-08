package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every Java snippet in the README compiles against the package, so the documentation cannot drift
 * from the API. A snippet that is a whole compilation unit compiles as written; any other becomes
 * the body of a method in a class whose fields stand for what a reader already has: {@code
 * credentialProvider}, {@code client}, {@code channel}, {@code chat}, {@code mapper} (Jackson) and
 * {@code gson}.
 */
class ReadmeTest {
  private static final Pattern JAVA_BLOCK = Pattern.compile("(?s)```java\n(.*?)```");

  private static final String IMPORTS =
      """
      import com.useceleris.client.*;
      import java.net.URI;
      import java.net.http.HttpClient;
      import java.net.http.HttpRequest;
      import java.net.http.HttpResponse;
      import java.nio.charset.StandardCharsets;
      import java.time.Duration;
      import java.time.Instant;
      import java.util.List;
      import java.util.Map;
      import java.util.concurrent.CompletableFuture;
      import java.util.concurrent.CompletionException;
      import java.util.concurrent.TimeUnit;
      """;

  private static final String FIELDS =
      """
        CredentialProvider credentialProvider;
        CelerisClient client;
        Channel channel;
        Segment chat;
        tools.jackson.databind.ObjectMapper mapper;
        com.google.gson.Gson gson;
      """;

  @TempDir Path directory;

  @Test
  void everySnippetCompiles() throws Exception {
    String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
    Matcher blocks = JAVA_BLOCK.matcher(readme);
    List<Path> sources = new ArrayList<>();
    List<String> snippets = new ArrayList<>();

    while (blocks.find()) {
      String snippet = blocks.group(1);
      int index = snippets.size();
      snippets.add(snippet);
      Path source = directory.resolve("Snippet" + index + ".java");
      boolean wholeUnit = snippet.startsWith("import ") || snippet.startsWith("public ");
      String unit =
          wholeUnit
              ? snippet
              : IMPORTS
                  + "\nclass Snippet"
                  + index
                  + " {\n"
                  + FIELDS
                  + "\n  void snippet() throws Exception {\n"
                  + snippet
                  + "\n  }\n}\n";
      Files.writeString(source, unit, StandardCharsets.UTF_8);
      sources.add(source);
    }

    assertFalse(snippets.isEmpty(), "the README has no Java snippets");

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

    try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
      boolean compiled =
          compiler
              .getTask(
                  null,
                  files,
                  diagnostics,
                  List.of(
                      "-proc:none",
                      "-classpath",
                      System.getProperty("java.class.path"),
                      "-d",
                      directory.resolve("classes").toString()),
                  null,
                  files.getJavaFileObjectsFromPaths(sources))
              .call();
      StringBuilder report = new StringBuilder();

      for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
        if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
          report
              .append(diagnostic.getSource().getName())
              .append(':')
              .append(diagnostic.getLineNumber())
              .append(' ')
              .append(diagnostic.getMessage(null))
              .append('\n');
        }
      }

      assertTrue(compiled, report.toString());
    }
  } // end method everySnippetCompiles
} // end class ReadmeTest
