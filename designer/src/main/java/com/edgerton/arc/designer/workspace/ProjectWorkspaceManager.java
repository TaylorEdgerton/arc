package com.edgerton.arc.designer.workspace;

import com.inductiveautomation.ignition.designer.model.DesignerContext;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the local scratch directory that serves as Claude Code's working directory. Located at
 * ~/.ignition-ai/projects/<project-name>/
 */
public class ProjectWorkspaceManager {

  private static final Logger log = LoggerFactory.getLogger("AI-Designer.Workspace");

  private final File workingDirectory;
  private final String projectName;
  private final Path sharedSkillsDirectory;

  private String lastWorkspaceClaudeState;
  private String lastWorkspaceSettingsState;
  private String lastSharedSkillsState;

  public ProjectWorkspaceManager(DesignerContext context) {
    this.projectName = context.getProjectName();
    this.sharedSkillsDirectory = ClaudeInstructionSupport.defaultSharedSkillsDirectory();

    String userHome = System.getProperty("user.home");
    this.workingDirectory = new File(userHome, ".ignition-ai/projects/" + projectName);

    if (!workingDirectory.exists()) {
      boolean created = workingDirectory.mkdirs();
      log.info("Created workspace directory: {} (success={})", workingDirectory, created);
    } else {
      log.info("Using existing workspace directory: {}", workingDirectory);
    }

    try {
      prepareClaudeContext();
    } catch (IOException e) {
      log.error("Failed to prepare Claude context files", e);
    }
  }

  /**
   * Ensures Claude Code's workspace instruction files exist and returns a fingerprint of the
   * effective instruction set.
   */
  public ClaudeContextSnapshot prepareClaudeContext() throws IOException {
    Path workspaceClaudeMd = ensureWorkspaceClaudeMd();
    Path workspaceSettingsJson = ensureWorkspaceSettingsJson();
    SharedSkillsSnapshot sharedSkills = syncSharedSkillsDirectory();
    String fingerprint =
        ClaudeInstructionSupport.computeContextFingerprint(
            workspaceClaudeMd, workspaceSettingsJson, sharedSkills.skillDirectories());
    return new ClaudeContextSnapshot(fingerprint, sharedSkills.skillDirectories().size());
  }

  private Path ensureWorkspaceClaudeMd() throws IOException {
    Path target = workingDirectory.toPath().resolve(ClaudeInstructionSupport.WORKSPACE_CLAUDE_MD);
    if (Files.exists(target)) {
      logWorkspaceClaudeState(
          "existing", "Using existing workspace CLAUDE.md: {}", target.toAbsolutePath());
      return target;
    }

    try (InputStream is = getClass().getResourceAsStream("/CLAUDE.md")) {
      if (is == null) {
        log.warn("Bundled CLAUDE.md not found in module resources");
        return target;
      }
      Files.copy(is, target);
      logWorkspaceClaudeState("seeded", "Seeded bundled CLAUDE.md to {}", target.toAbsolutePath());
      return target;
    }
  }

  private Path ensureWorkspaceSettingsJson() throws IOException {
    Path target =
        workingDirectory.toPath().resolve(ClaudeInstructionSupport.WORKSPACE_SETTINGS_JSON);
    if (Files.exists(target)) {
      logWorkspaceSettingsState(
          "existing", "Using existing workspace Claude settings: {}", target.toAbsolutePath());
      return target;
    }

    Files.createDirectories(target.getParent());
    Files.writeString(
        target, ClaudeInstructionSupport.defaultWorkspaceSettingsJson(), StandardCharsets.UTF_8);
    logWorkspaceSettingsState(
        "seeded", "Seeded workspace Claude settings to {}", target.toAbsolutePath());
    return target;
  }

  public File getWorkingDirectory() {
    return workingDirectory;
  }

  public String getProjectName() {
    return projectName;
  }

  public Path getWorkspaceDocumentHome() {
    return WorkspaceDocumentSupport.workspaceDocumentHome(workingDirectory.toPath());
  }

  public SavedDocument saveChatTranscript(String markdown) throws IOException {
    Path transcriptPath =
        WorkspaceDocumentSupport.nextTranscriptPath(
            workingDirectory.toPath(), Clock.systemDefaultZone());
    Files.createDirectories(transcriptPath.getParent());
    Files.writeString(transcriptPath, markdown == null ? "" : markdown, StandardCharsets.UTF_8);
    log.info("Saved chat transcript to {}", transcriptPath.toAbsolutePath());
    return new SavedDocument(transcriptPath, ClaudeInstructionSupport.displayPath(transcriptPath));
  }

  public void openInFileBrowser() {
    try {
      openPath(workingDirectory.toPath());
    } catch (IOException e) {
      log.error("Failed to open workspace folder: {}", e.getMessage());
    }
  }

  public void openPath(Path path) throws IOException {
    if (Desktop.isDesktopSupported()) {
      Desktop.getDesktop().open(path.toFile());
    }
  }

  private SharedSkillsSnapshot syncSharedSkillsDirectory() throws IOException {
    Path workspaceRoot = workingDirectory.toPath();
    Path workspaceSkillsDirectory =
        workspaceRoot.resolve(ClaudeInstructionSupport.WORKSPACE_SKILLS_DIRECTORY);
    Path manifestPath =
        workspaceRoot.resolve(ClaudeInstructionSupport.MANAGED_SHARED_SKILLS_MANIFEST_PATH);

    cleanupLegacyManagedRulesFile(workspaceRoot);

    Set<String> previouslyManagedSkills =
        ClaudeInstructionSupport.readManagedSkillManifest(manifestPath);

    if (!Files.exists(sharedSkillsDirectory)) {
      removeManagedSkillDirectories(
          workspaceSkillsDirectory, previouslyManagedSkills, manifestPath);
      logSharedSkillsState(
          "missing",
          "Shared skills directory {} not found; proceeding with workspace CLAUDE.md only",
          ClaudeInstructionSupport.displayPath(sharedSkillsDirectory));
      return new SharedSkillsSnapshot(List.of());
    }

    if (!Files.isDirectory(sharedSkillsDirectory)) {
      removeManagedSkillDirectories(
          workspaceSkillsDirectory, previouslyManagedSkills, manifestPath);
      logSharedSkillsState(
          "not-directory",
          "Shared skills path {} is not a directory; proceeding with workspace CLAUDE.md only",
          ClaudeInstructionSupport.displayPath(sharedSkillsDirectory));
      return new SharedSkillsSnapshot(List.of());
    }

    List<ClaudeInstructionSupport.SharedSkillDirectory> sharedSkillDirectories =
        ClaudeInstructionSupport.discoverSharedSkillDirectories(sharedSkillsDirectory);
    if (sharedSkillDirectories.isEmpty()) {
      removeManagedSkillDirectories(
          workspaceSkillsDirectory, previouslyManagedSkills, manifestPath);
      logSharedSkillsState(
          "empty",
          "Shared skills directory {} is empty; proceeding with workspace CLAUDE.md only",
          ClaudeInstructionSupport.displayPath(sharedSkillsDirectory));
      return new SharedSkillsSnapshot(List.of());
    }

    Files.createDirectories(workspaceSkillsDirectory);
    TreeSet<String> managedSkillNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    List<ClaudeInstructionSupport.SharedSkillDirectory> activeSharedSkills =
        new java.util.ArrayList<>();

    for (ClaudeInstructionSupport.SharedSkillDirectory sharedSkillDirectory :
        sharedSkillDirectories) {
      String skillName = sharedSkillDirectory.skillName();
      Path targetSkillDirectory = workspaceSkillsDirectory.resolve(skillName);
      boolean targetExists = Files.exists(targetSkillDirectory);
      boolean previouslyManaged = previouslyManagedSkills.contains(skillName);

      if (targetExists && !previouslyManaged) {
        log.warn(
            "Skipping shared skill '{}' because workspace skill directory already exists at {}",
            skillName,
            targetSkillDirectory);
        continue;
      }

      if (targetExists) {
        ClaudeInstructionSupport.deleteRecursively(targetSkillDirectory);
      }
      ClaudeInstructionSupport.copyDirectoryRecursive(
          sharedSkillDirectory.directory(), targetSkillDirectory);
      managedSkillNames.add(skillName);
      activeSharedSkills.add(sharedSkillDirectory);
    }

    for (String oldSkillName : previouslyManagedSkills) {
      if (!managedSkillNames.contains(oldSkillName)) {
        ClaudeInstructionSupport.deleteRecursively(workspaceSkillsDirectory.resolve(oldSkillName));
      }
    }

    ClaudeInstructionSupport.writeManagedSkillManifest(manifestPath, managedSkillNames);

    String loadedState =
        "loaded:"
            + managedSkillNames.size()
            + ":"
            + Integer.toHexString(activeSharedSkills.hashCode());
    logSharedSkillsState(
        loadedState,
        "Loaded {} shared skills from {} into {}",
        activeSharedSkills.size(),
        ClaudeInstructionSupport.displayPath(sharedSkillsDirectory),
        workspaceSkillsDirectory.toAbsolutePath());
    return new SharedSkillsSnapshot(activeSharedSkills);
  }

  private void removeManagedSkillDirectories(
      Path workspaceSkillsDirectory, Set<String> managedSkillNames, Path manifestPath)
      throws IOException {
    for (String skillName : managedSkillNames) {
      ClaudeInstructionSupport.deleteRecursively(workspaceSkillsDirectory.resolve(skillName));
    }
    ClaudeInstructionSupport.writeManagedSkillManifest(manifestPath, Set.of());
    deleteDirectoryIfEmpty(workspaceSkillsDirectory);
    deleteDirectoryIfEmpty(manifestPath.getParent());
  }

  private void cleanupLegacyManagedRulesFile(Path workspaceRoot) throws IOException {
    Path legacyRulesFile =
        workspaceRoot.resolve(ClaudeInstructionSupport.LEGACY_MANAGED_RULES_PATH);
    Files.deleteIfExists(legacyRulesFile);
    deleteDirectoryIfEmpty(legacyRulesFile.getParent());
  }

  private void deleteDirectoryIfEmpty(Path directory) throws IOException {
    if (directory == null || !Files.isDirectory(directory)) {
      return;
    }

    try (var stream = Files.list(directory)) {
      if (stream.findAny().isEmpty()) {
        Files.deleteIfExists(directory);
      }
    }
  }

  private void logWorkspaceClaudeState(String state, String message, Object arg) {
    if (!state.equals(lastWorkspaceClaudeState)) {
      lastWorkspaceClaudeState = state;
      log.info(message, arg);
    }
  }

  private void logSharedSkillsState(String state, String message, Object... args) {
    if (!state.equals(lastSharedSkillsState)) {
      lastSharedSkillsState = state;
      log.info(message, args);
    }
  }

  private void logWorkspaceSettingsState(String state, String message, Object arg) {
    if (!state.equals(lastWorkspaceSettingsState)) {
      lastWorkspaceSettingsState = state;
      log.info(message, arg);
    }
  }

  public record ClaudeContextSnapshot(String fingerprint, int sharedSkillCount) {}

  public record SavedDocument(Path path, String displayPath) {}

  private record SharedSkillsSnapshot(
      List<ClaudeInstructionSupport.SharedSkillDirectory> skillDirectories) {}
}
