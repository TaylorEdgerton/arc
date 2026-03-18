package com.edgerton.arc.designer.claude;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import javax.swing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Validates that the Claude Code CLI is installed on the developer's machine. */
public class ClaudeCodeSetupChecker {

  private static final Logger log = LoggerFactory.getLogger("AI-Designer.SetupChecker");

  private String claudeVersion;

  public boolean isAvailable() {
    try {
      ProcessBuilder pb = new ProcessBuilder("claude", "--version");
      pb.redirectErrorStream(true);
      Process proc = pb.start();

      StringBuilder output = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line);
        }
      }

      int exitCode = proc.waitFor();
      if (exitCode == 0) {
        claudeVersion = output.toString().trim();
        log.info("Claude Code found: {}", claudeVersion);
        return true;
      } else {
        log.warn("Claude Code check returned exit code {}: {}", exitCode, output);
        return false;
      }
    } catch (Exception e) {
      log.warn("Claude Code CLI not found: {}", e.getMessage());
      return false;
    }
  }

  public String getVersion() {
    return claudeVersion;
  }

  public void showNotInstalledDialog() {
    String message =
        "Claude Code CLI is not installed on this machine.\n\n"
            + "The AI Designer Assistant requires Claude Code to function.\n\n"
            + "Install it with:\n"
            + "  npm install -g @anthropic-ai/claude-code\n\n"
            + "Then configure your API key:\n"
            + "  claude config\n\n"
            + "Restart the Designer after installation.";

    SwingUtilities.invokeLater(
        () ->
            JOptionPane.showMessageDialog(
                null,
                message,
                "AI Designer Assistant — Setup Required",
                JOptionPane.WARNING_MESSAGE));
  }
}
