package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * AUTH-05 on the artifact itself: the jar and its sources carry no signing facility, the module
 * requires nothing beyond the JDK, and the published POM declares no runtime dependency.
 */
class ArtifactIT {
  private static final List<String> FORBIDDEN =
      List.of(
          "javax/crypto",
          "javax.crypto",
          "java/security/MessageDigest",
          "MessageDigest",
          "Hmac",
          "SHA512",
          "com/useceleris/server",
          "com.useceleris.server",
          "signingSecret");

  private static JarFile jar(String property) throws IOException {
    return new JarFile(System.getProperty(property));
  } // end method jar

  @Test
  void theJarHoldsOnlyThePackageAndItsMetadata() throws IOException {
    try (JarFile jar = jar("celeris.jar")) {
      for (String name : names(jar)) {
        boolean allowed =
            name.equals("module-info.class")
                || name.startsWith("com/useceleris/client/") && name.endsWith(".class")
                || name.startsWith("META-INF/");
        assertTrue(allowed, "unexpected entry " + name);
        assertFalse(name.contains("examples") || name.endsWith("Test.class"), name);
      }
    }
  } // end method theJarHoldsOnlyThePackageAndItsMetadata

  @Test
  void neitherTheJarNorItsSourcesMentionASigningFacility() throws IOException {
    for (String property : List.of("celeris.jar", "celeris.sourcesJar")) {
      try (JarFile jar = jar(property)) {
        Enumeration<JarEntry> entries = jar.entries();

        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();

          if (entry.isDirectory() || entry.getName().startsWith("META-INF/maven/")) {
            continue;
          }

          String content;

          try (InputStream stream = jar.getInputStream(entry)) {
            content = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
          }

          for (String forbidden : FORBIDDEN) {
            assertFalse(content.contains(forbidden), entry.getName() + " mentions " + forbidden);
          }
        }
      }
    }
  } // end method neitherTheJarNorItsSourcesMentionASigningFacility

  @Test
  void theModuleRequiresOnlyTheJdk() throws IOException {
    try (JarFile jar = jar("celeris.jar");
        InputStream descriptor = jar.getInputStream(jar.getEntry("module-info.class"))) {
      ModuleDescriptor module = ModuleDescriptor.read(descriptor);
      Set<String> requires = new TreeSet<>();
      module.requires().forEach(requirement -> requires.add(requirement.name()));

      assertEquals("com.useceleris.client", module.name());
      assertEquals(Set.of("java.base", "java.net.http"), requires);
      assertEquals(1, module.exports().size());
    }
  } // end method theModuleRequiresOnlyTheJdk

  @Test
  void thePublishedPomDeclaresNoRuntimeDependency() throws Exception {
    try (JarFile jar = jar("celeris.jar");
        InputStream pom =
            jar.getInputStream(
                jar.getJarEntry("META-INF/maven/com.useceleris/celeris-client/pom.xml"))) {
      org.w3c.dom.Element project =
          javax.xml.parsers.DocumentBuilderFactory.newInstance()
              .newDocumentBuilder()
              .parse(pom)
              .getDocumentElement();

      for (org.w3c.dom.Node child = project.getFirstChild();
          child != null;
          child = child.getNextSibling()) {
        if (!"dependencies".equals(child.getNodeName())) {
          continue;
        }

        org.w3c.dom.NodeList dependencies =
            ((org.w3c.dom.Element) child).getElementsByTagName("dependency");

        for (int index = 0; index < dependencies.getLength(); index++) {
          org.w3c.dom.Element dependency = (org.w3c.dom.Element) dependencies.item(index);
          org.w3c.dom.NodeList scope = dependency.getElementsByTagName("scope");
          String artifact = dependency.getElementsByTagName("artifactId").item(0).getTextContent();

          assertTrue(
              scope.getLength() == 1 && scope.item(0).getTextContent().equals("test"),
              "runtime dependency: " + artifact);
        }
      }
    }
  } // end method thePublishedPomDeclaresNoRuntimeDependency

  private static List<String> names(JarFile jar) {
    List<String> names = new ArrayList<>();
    jar.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> names.add(entry.getName()));

    return names;
  } // end method names
} // end class ArtifactIT
