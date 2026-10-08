package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * The house layout rules google-java-format does not enforce, checked on every source, test and
 * example file:
 *
 * <ul>
 *   <li>a statement that ends with a block on a later line than it starts (an {@code if}, a loop, a
 *       {@code try}, a lambda spanning lines) is followed by a blank line, unless it is the last in
 *       its block;
 *   <li>members of a type are separated by a blank line, except consecutive fields;
 *   <li>every method, constructor and named type whose body spans lines carries a marker after its
 *       closing brace that names it: {@code // end method name}, {@code // end constructor Name},
 *       {@code // end class Name}, and likewise for {@code interface}, {@code enum}, {@code record}
 *       and {@code annotation}.
 * </ul>
 */
class LayoutTest {
  private static final List<String> ROOTS = List.of("src/main/java", "src/test/java", "examples");

  @Test
  void everySourceFileFollowsTheLayoutRules() throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    List<String> violations = new ArrayList<>();

    try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
      for (Path source : sources()) {
        JavacTask task =
            (JavacTask)
                compiler.getTask(
                    null, files, null, List.of(), null, files.getJavaFileObjects(source));
        CompilationUnitTree unit = task.parse().iterator().next();
        String[] lines = Files.readString(source, StandardCharsets.UTF_8).split("\n", -1);
        new LayoutScanner(
                source, unit, Trees.instance(task).getSourcePositions(), lines, violations)
            .scan(unit, null);
      }
    }

    assertEquals(List.of(), violations, violations.size() + " layout violations");
  } // end method everySourceFileFollowsTheLayoutRules

  private static List<Path> sources() throws IOException {
    List<Path> sources = new ArrayList<>();

    for (String root : ROOTS) {
      try (Stream<Path> walk = Files.walk(Path.of(root))) {
        walk.filter(path -> path.toString().endsWith(".java")).sorted().forEach(sources::add);
      }
    }

    return sources;
  } // end method sources

  /** Collects each violation in one compilation unit as "path:line: rule". */
  private static final class LayoutScanner extends TreeScanner<Void, Void> {
    private final Path source;
    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final LineMap lineMap;
    private final String[] lines;
    private final List<String> violations;
    private final List<String> typeNames = new ArrayList<>();

    LayoutScanner(
        Path source,
        CompilationUnitTree unit,
        SourcePositions positions,
        String[] lines,
        List<String> violations) {
      this.source = source;
      this.unit = unit;
      this.positions = positions;
      this.lineMap = unit.getLineMap();
      this.lines = lines;
      this.violations = violations;
    } // end constructor LayoutScanner

    private long firstLine(Tree tree) {
      return lineMap.getLineNumber(positions.getStartPosition(unit, tree));
    } // end method firstLine

    private long lastLine(Tree tree) {
      return lineMap.getLineNumber(positions.getEndPosition(unit, tree) - 1);
    } // end method lastLine

    private String line(long lineNumber) {
      return lines[(int) lineNumber - 1];
    } // end method line

    private void report(long lineNumber, String rule) {
      violations.add(source + ":" + lineNumber + ": " + rule);
    } // end method report

    private void requireBlankLineAfter(Tree tree, String rule) {
      long last = lastLine(tree);

      if (last < lines.length && !line(last + 1).isBlank()) {
        report(last, rule);
      }
    } // end method requireBlankLineAfter

    private void checkStatements(List<? extends Tree> statements) {
      for (int index = 0; index + 1 < statements.size(); index++) {
        Tree statement = statements.get(index);
        boolean endsWithBlock =
            firstLine(statement) != lastLine(statement)
                && line(lastLine(statement)).trim().startsWith("}");

        if (endsWithBlock) {
          requireBlankLineAfter(statement, "a blank line must follow a block");
        }
      }
    } // end method checkStatements

    private void requireMarker(Tree tree, String kind, String name) {
      long closingBrace = positions.getEndPosition(unit, tree) - 1;

      if (firstLine(tree) == lineMap.getLineNumber(closingBrace)) {
        return;
      }

      String marker = " // end " + kind + " " + name;
      long closingLine = lineMap.getLineNumber(closingBrace);
      int column = (int) lineMap.getColumnNumber(closingBrace);

      if (!line(closingLine).substring(column).equals(marker)) {
        report(closingLine, "the closing brace must carry \"" + marker.trim() + "\"");
      }
    } // end method requireMarker

    @Override
    public Void visitBlock(BlockTree block, Void unused) {
      checkStatements(block.getStatements());

      return super.visitBlock(block, unused);
    } // end method visitBlock

    @Override
    public Void visitCase(CaseTree caseTree, Void unused) {
      if (caseTree.getStatements() != null) {
        checkStatements(caseTree.getStatements());
      }

      return super.visitCase(caseTree, unused);
    } // end method visitCase

    @Override
    public Void visitClass(ClassTree type, Void unused) {
      // A record's components are members too, written in its header.
      List<? extends Tree> members =
          type.getMembers().stream()
              .filter(member -> type.getKind() != Tree.Kind.RECORD || !isInstanceField(member))
              .toList();

      for (int index = 0; index + 1 < members.size(); index++) {
        if (!(members.get(index) instanceof VariableTree)
            || !(members.get(index + 1) instanceof VariableTree)) {
          requireBlankLineAfter(members.get(index), "a blank line must separate members");
        }
      }

      String name = type.getSimpleName().toString();

      // An anonymous class has no name to mark.
      if (!name.isEmpty()) {
        String kind =
            switch (type.getKind()) {
              case INTERFACE -> "interface";
              case ENUM -> "enum";
              case RECORD -> "record";
              case ANNOTATION_TYPE -> "annotation";
              default -> "class";
            };

        requireMarker(type, kind, name);
      }

      typeNames.add(name);

      try {
        return super.visitClass(type, unused);
      } finally {
        typeNames.remove(typeNames.size() - 1);
      }
    } // end method visitClass

    @Override
    public Void visitMethod(MethodTree method, Void unused) {
      if (method.getBody() != null) {
        if (method.getName().contentEquals("<init>")) {
          requireMarker(method, "constructor", typeNames.get(typeNames.size() - 1));
        } else {
          requireMarker(method, "method", method.getName().toString());
        }
      }

      return super.visitMethod(method, unused);
    } // end method visitMethod

    private static boolean isInstanceField(Tree member) {
      return member instanceof VariableTree field
          && !field.getModifiers().getFlags().contains(Modifier.STATIC);
    } // end method isInstanceField
  } // end class LayoutScanner
} // end class LayoutTest
