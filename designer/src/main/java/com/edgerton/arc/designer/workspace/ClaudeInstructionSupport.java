package com.edgerton.arc.designer.workspace;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ClaudeInstructionSupport {

  static final String WORKSPACE_CLAUDE_MD = "CLAUDE.md";
  static final String SKILL_ENTRYPOINT = "SKILL.md";
  static final Path WORKSPACE_SKILLS_DIRECTORY = Path.of(".claude", "skills");
  static final Path WORKSPACE_SETTINGS_JSON = Path.of(".claude", "settings.json");
  static final Path MANAGED_SHARED_SKILLS_MANIFEST_PATH =
      Path.of(".claude", ".ignition-ai-managed-skills");
  static final Path LEGACY_MANAGED_RULES_PATH =
      Path.of(".claude", "rules", "ignition-ai-shared-skills.md");

  private static final Comparator<Path> PATH_NAME_ORDER =
      Comparator.comparing(
              (Path path) -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)
          .thenComparing(path -> path.getFileName().toString());

  private ClaudeInstructionSupport() {}

  static Path defaultSharedSkillsDirectory() {
    return Path.of(System.getProperty("user.home"), ".ignition-ai", "skills");
  }

  static String defaultWorkspaceSettingsJson() {
    return "{\n"
        + "  \"permissions\": {\n"
        + "    \"allow\": [\n"
        + "      \"Read\"\n"
        + "    ]\n"
        + "  }\n"
        + "}\n";
  }

  static List<SharedSkillDirectory> discoverSharedSkillDirectories(Path skillsDirectory)
      throws IOException {
    if (!Files.isDirectory(skillsDirectory)) {
      return List.of();
    }

    List<SharedSkillDirectory> skillDirectories = new ArrayList<>();
    try (Stream<Path> stream = Files.list(skillsDirectory)) {
      stream
          .filter(Files::isDirectory)
          .filter(path -> Files.isRegularFile(path.resolve(SKILL_ENTRYPOINT)))
          .sorted(PATH_NAME_ORDER)
          .forEach(
              path ->
                  skillDirectories.add(
                      new SharedSkillDirectory(path.getFileName().toString(), path)));
    }
    return List.copyOf(skillDirectories);
  }

  static Set<String> readManagedSkillManifest(Path manifestPath) throws IOException {
    if (!Files.isRegularFile(manifestPath)) {
      return Set.of();
    }

    try (Stream<String> lines = Files.lines(manifestPath, StandardCharsets.UTF_8)) {
      LinkedHashSet<String> entries =
          lines
              .map(String::trim)
              .filter(line -> !line.isEmpty())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      return Set.copyOf(entries);
    }
  }

  static void writeManagedSkillManifest(Path manifestPath, Collection<String> skillNames)
      throws IOException {
    if (skillNames.isEmpty()) {
      Files.deleteIfExists(manifestPath);
      return;
    }

    Files.createDirectories(manifestPath.getParent());
    String content = String.join("\n", skillNames) + "\n";
    Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
  }

  static void copyDirectoryRecursive(Path sourceDirectory, Path targetDirectory)
      throws IOException {
    try (Stream<Path> stream = Files.walk(sourceDirectory)) {
      for (Path sourcePath : stream.sorted().toList()) {
        Path relativePath = sourceDirectory.relativize(sourcePath);
        Path targetPath = targetDirectory.resolve(relativePath);
        if (Files.isDirectory(sourcePath)) {
          Files.createDirectories(targetPath);
        } else if (Files.isRegularFile(sourcePath)) {
          Files.createDirectories(targetPath.getParent());
          Files.copy(
              sourcePath,
              targetPath,
              java.nio.file.StandardCopyOption.REPLACE_EXISTING,
              java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    }
  }

  static void deleteRecursively(Path path) throws IOException {
    if (path == null || !Files.exists(path)) {
      return;
    }

    try (Stream<Path> stream = Files.walk(path)) {
      for (Path current : stream.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(current);
      }
    }
  }

  static String computeContextFingerprint(
      Path workspaceClaudeMd,
      Path workspaceSettingsJson,
      List<SharedSkillDirectory> sharedSkillDirectories)
      throws IOException {
    MessageDigest digest = newSha256();
    updateDigestWithFile(digest, workspaceClaudeMd);
    updateDigestWithFile(digest, workspaceSettingsJson);
    for (SharedSkillDirectory sharedSkillDirectory : sharedSkillDirectories) {
      updateDigestWithString(digest, sharedSkillDirectory.skillName());
      updateDigestWithDirectory(digest, sharedSkillDirectory.directory());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static String displayPath(Path path) {
    Path normalizedPath = path.toAbsolutePath().normalize();
    Path userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();

    if (normalizedPath.startsWith(userHome)) {
      Path relativePath = userHome.relativize(normalizedPath);
      if (relativePath.getNameCount() == 0) {
        return "~";
      }
      return "~/" + relativePath.toString().replace(File.separatorChar, '/');
    }

    return normalizedPath.toString();
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 digest unavailable", e);
    }
  }

  private static void updateDigestWithDirectory(MessageDigest digest, Path directory)
      throws IOException {
    if (!Files.exists(directory)) {
      updateDigestWithString(digest, "<missing-directory>");
      return;
    }

    try (Stream<Path> stream = Files.walk(directory)) {
      for (Path current : stream.sorted().toList()) {
        Path relativePath = directory.relativize(current);
        updateDigestWithString(digest, relativePath.toString());
        if (Files.isRegularFile(current)) {
          updateDigestWithFileContents(digest, current);
        }
      }
    }
  }

  private static void updateDigestWithFile(MessageDigest digest, Path path) throws IOException {
    updateDigestWithString(digest, path.toAbsolutePath().normalize().toString());
    if (!Files.exists(path)) {
      updateDigestWithString(digest, "<missing>");
      return;
    }
    updateDigestWithFileContents(digest, path);
  }

  private static void updateDigestWithFileContents(MessageDigest digest, Path path)
      throws IOException {
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = input.read(buffer)) != -1) {
        digest.update(buffer, 0, bytesRead);
      }
    }
  }

  private static void updateDigestWithString(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) '\n');
  }

  record SharedSkillDirectory(String skillName, Path directory) {}
}
