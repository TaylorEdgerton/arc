package com.edgerton.arc.designer.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeInstructionSupportTest {

  @TempDir Path tempDir;

  @Test
  void discoverSharedSkillDirectoriesReturnsSortedTopLevelSkillDirectoriesOnly() throws Exception {
    Path alpha = createSkillDirectory("Alpha", "Alpha instructions");
    Path beta = createSkillDirectory("beta", "Beta instructions");
    Files.writeString(tempDir.resolve("notes.txt"), "ignore me", StandardCharsets.UTF_8);
    Path nestedParent = Files.createDirectories(tempDir.resolve("nested"));
    Files.createDirectories(nestedParent.resolve("gamma"));
    Files.writeString(
        nestedParent.resolve("gamma").resolve("SKILL.md"), "nested", StandardCharsets.UTF_8);

    List<ClaudeInstructionSupport.SharedSkillDirectory> skillDirectories =
        ClaudeInstructionSupport.discoverSharedSkillDirectories(tempDir);

    assertEquals(
        List.of(
            new ClaudeInstructionSupport.SharedSkillDirectory("Alpha", alpha),
            new ClaudeInstructionSupport.SharedSkillDirectory("beta", beta)),
        skillDirectories);
  }

  @Test
  void copyDirectoryRecursiveCopiesSupportingFiles() throws Exception {
    Path sourceSkill = createSkillDirectory("alpha", "Alpha instructions");
    Files.writeString(
        sourceSkill.resolve("examples").resolve("usage.md"), "Usage", StandardCharsets.UTF_8);
    Path targetSkill = tempDir.resolve("target").resolve("alpha");

    ClaudeInstructionSupport.copyDirectoryRecursive(sourceSkill, targetSkill);

    assertTrue(Files.isRegularFile(targetSkill.resolve("SKILL.md")));
    assertEquals(
        "Usage",
        Files.readString(
            targetSkill.resolve("examples").resolve("usage.md"), StandardCharsets.UTF_8));
  }

  @Test
  void computeContextFingerprintChangesWhenSupportingFileChanges() throws Exception {
    Path workspaceClaude =
        Files.writeString(tempDir.resolve("CLAUDE.md"), "root", StandardCharsets.UTF_8);
    Path workspaceClaudeDirectory = Files.createDirectories(tempDir.resolve(".claude"));
    Path workspaceSettings =
        Files.writeString(
            workspaceClaudeDirectory.resolve("settings.json"),
            ClaudeInstructionSupport.defaultWorkspaceSettingsJson(),
            StandardCharsets.UTF_8);
    Path skillDirectory = createSkillDirectory("alpha", "Alpha instructions");
    Path supportingFile =
        Files.writeString(skillDirectory.resolve("notes.md"), "one", StandardCharsets.UTF_8);

    List<ClaudeInstructionSupport.SharedSkillDirectory> skillDirectories =
        List.of(new ClaudeInstructionSupport.SharedSkillDirectory("alpha", skillDirectory));
    String originalFingerprint =
        ClaudeInstructionSupport.computeContextFingerprint(
            workspaceClaude, workspaceSettings, skillDirectories);

    Files.writeString(supportingFile, "two", StandardCharsets.UTF_8);

    String updatedFingerprint =
        ClaudeInstructionSupport.computeContextFingerprint(
            workspaceClaude, workspaceSettings, skillDirectories);

    assertNotEquals(originalFingerprint, updatedFingerprint);
  }

  @Test
  void defaultWorkspaceSettingsJsonAllowsReadOnlyByDefault() {
    String settingsJson = ClaudeInstructionSupport.defaultWorkspaceSettingsJson();

    assertTrue(settingsJson.contains("\"permissions\""));
    assertTrue(settingsJson.contains("\"Read\""));
  }

  private Path createSkillDirectory(String name, String skillBody) throws Exception {
    Path directory = Files.createDirectories(tempDir.resolve(name));
    Files.createDirectories(directory.resolve("examples"));
    Files.writeString(directory.resolve("SKILL.md"), skillBody + "\n", StandardCharsets.UTF_8);
    return directory;
  }
}
