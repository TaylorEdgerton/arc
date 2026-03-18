package com.edgerton.arc.designer.claude;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages Claude Code interactions using per-turn process spawning.
 *
 * <p>Each user message spawns: claude -p "message" --output-format stream-json [--resume sessionId]
 * Multi-turn continuity is maintained via --resume with the session ID from the previous turn.
 */
public class ClaudeCodeManager {

  private static final Logger log = LoggerFactory.getLogger("AI-Designer.ClaudeCode");
  public static final String MCP_SERVER_ID = "ignition-designer";
  public static final String PERMISSION_PROMPT_TOOL_NAME = "permission_prompt";
  static final String FILE_TOOLS = "Read,Write,Edit,MultiEdit,Skill,Task,Glob";
  static final String ALLOWED_MCP_TOOLS = "mcp__" + MCP_SERVER_ID + "__*";
  static final String PERMISSION_PROMPT_TOOL =
      "mcp__" + MCP_SERVER_ID + "__" + PERMISSION_PROMPT_TOOL_NAME;

  private File workingDir;
  private volatile boolean available;
  private volatile boolean busy;
  private String sessionId;
  private String sessionContextFingerprint;
  private Process currentProcess;

  private Consumer<String> onTextChunk;
  private Consumer<String> onToolStatus;
  private Runnable onTurnComplete;

  public void setOnTextChunk(Consumer<String> handler) {
    this.onTextChunk = handler;
  }

  public void setOnToolStatus(Consumer<String> handler) {
    this.onToolStatus = handler;
  }

  public void setOnTurnComplete(Runnable handler) {
    this.onTurnComplete = handler;
  }

  /**
   * Initializes the manager — writes MCP config, marks as available. Does NOT start a long-running
   * process.
   */
  public void start(File workingDir, int mcpPort) throws IOException {
    this.workingDir = workingDir;
    resetSessionState();

    // Write MCP config file — Claude Code reads .mcp.json from working directory
    File mcpConfig = new File(workingDir, ".mcp.json");
    String configJson =
        "{\n"
            + "  \"mcpServers\": {\n"
            + "    \""
            + MCP_SERVER_ID
            + "\": {\n"
            + "      \"type\": \"http\",\n"
            + "      \"url\": \"http://127.0.0.1:"
            + mcpPort
            + "/mcp\"\n"
            + "    }\n"
            + "  }\n"
            + "}";
    Files.writeString(mcpConfig.toPath(), configJson, StandardCharsets.UTF_8);
    log.info("Wrote MCP config to {}", mcpConfig);

    available = true;
    log.info("ClaudeCodeManager ready — working dir: {}", workingDir);
  }

  /**
   * Sends a user message by spawning a Claude Code process for this turn. Runs on a background
   * thread — calls callbacks on that thread (caller must dispatch to EDT).
   */
  public void sendMessage(String message, String contextFingerprint) {
    if (!available) {
      log.warn("Cannot send message — ClaudeCodeManager not initialized");
      return;
    }
    if (busy) {
      log.warn("Cannot send message — previous turn still in progress");
      return;
    }

    String normalizedFingerprint = Objects.requireNonNullElse(contextFingerprint, "");
    if (sessionId != null && !Objects.equals(normalizedFingerprint, sessionContextFingerprint)) {
      log.info("Claude instruction context changed; starting a fresh session");
      resetSessionState();
    }
    if (sessionId == null) {
      sessionContextFingerprint = normalizedFingerprint;
    }

    busy = true;

    Thread thread =
        new Thread(
            () -> {
              try {
                executeTurn(message);
              } catch (Exception e) {
                log.error("Turn execution failed", e);
                if (onTextChunk != null) {
                  onTextChunk.accept("\nError: " + e.getMessage() + "\n");
                }
              } finally {
                busy = false;
                currentProcess = null;
                if (onTurnComplete != null) {
                  onTurnComplete.run();
                }
              }
            },
            "ClaudeCode-Turn");
    thread.setDaemon(true);
    thread.start();
  }

  private void executeTurn(String message) throws IOException, InterruptedException {
    List<String> command = buildCommand(message, sessionId);

    log.info(
        "Spawning Claude Code: {} (session={})",
        message.length() > 80 ? message.substring(0, 80) + "..." : message,
        sessionId != null ? sessionId : "new");
    log.info("Full command: {}", String.join(" ", command));

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(workingDir);
    pb.redirectErrorStream(false);

    currentProcess = pb.start();

    // Close stdin immediately — we passed the message via -p flag
    currentProcess.getOutputStream().close();

    // Read stderr on a separate thread so we can capture error output
    StringBuilder stderrOutput = new StringBuilder();
    Thread stderrReader =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(
                          currentProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  stderrOutput.append(line).append("\n");
                  log.debug("Claude stderr: {}", line);
                }
              } catch (IOException e) {
                log.debug("Stderr reader error: {}", e.getMessage());
              }
            },
            "ClaudeCode-Stderr");
    stderrReader.setDaemon(true);
    stderrReader.start();

    // Read stdout line by line (stream-json NDJSON)
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(currentProcess.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        parseLine(line.trim());
      }
    }

    int exitCode = currentProcess.waitFor();
    stderrReader.join(2000); // Wait for stderr to finish reading
    log.info("Claude Code process exited with code {}", exitCode);

    if (exitCode != 0) {
      String stderr = stderrOutput.toString().trim();
      log.error("Claude Code failed (exit {}): {}", exitCode, stderr);
      if (onTextChunk != null && !stderr.isEmpty()) {
        onTextChunk.accept("\nClaude Code error:\n" + stderr + "\n");
      } else if (onTextChunk != null) {
        onTextChunk.accept("\nClaude Code exited with code " + exitCode + " (no stderr output)\n");
      }
    }
  }

  static List<String> buildCommand(String message, String sessionId) {
    List<String> command = new ArrayList<>();
    command.add("claude");
    command.add("-p");
    command.add(message);
    command.add("--output-format");
    command.add("stream-json");
    command.add("--verbose");
    command.add("--tools");
    command.add(FILE_TOOLS);
    command.add("--allowedTools");
    command.add(ALLOWED_MCP_TOOLS + "," + "Read,Skill,Task,Glob");
    command.add("--permission-prompt-tool");
    command.add(PERMISSION_PROMPT_TOOL);

    if (sessionId != null) {
      command.add("--resume");
      command.add(sessionId);
    }

    return command;
  }

  public boolean isRunning() {
    return available;
  }

  public boolean isBusy() {
    return busy;
  }

  /**
   * Cancels the current in-progress turn by killing the process. Unlike stop(), this keeps the
   * manager available for new turns.
   */
  public void cancelCurrentTurn() {
    if (!busy) return;
    log.info("Cancelling current turn");
    if (currentProcess != null && currentProcess.isAlive()) {
      currentProcess.destroyForcibly();
    }
    // busy flag and onTurnComplete callback are handled by the finally block in sendMessage's
    // thread
  }

  public void stop() {
    available = false;
    if (currentProcess != null && currentProcess.isAlive()) {
      currentProcess.destroyForcibly();
    }
    resetSessionState();
    log.info("ClaudeCodeManager stopped");
  }

  public void resetSession() {
    if (busy) {
      log.warn("Cannot reset Claude Code session while a turn is still in progress");
      return;
    }
    resetSessionState();
    log.info("Claude Code session reset");
  }

  /** Parses a single stream-json line from Claude Code stdout. */
  private void parseLine(String line) {
    try {
      JsonObject event = JsonParser.parseString(line).getAsJsonObject();
      String type = event.has("type") ? event.get("type").getAsString() : "";

      switch (type) {
        case "system":
          handleSystemEvent(event);
          break;
        case "assistant":
          handleAssistantEvent(event);
          break;
        case "user":
          // Synthetic tool result messages — skip display
          break;
        case "result":
          handleResultEvent(event);
          break;
        default:
          log.debug("Unknown stream event type: {}", type);
          break;
      }
    } catch (JsonSyntaxException e) {
      log.debug("Non-JSON output: {}", line);
    }
  }

  private void handleSystemEvent(JsonObject event) {
    String subtype = event.has("subtype") ? event.get("subtype").getAsString() : "";
    if ("init".equals(subtype)) {
      // Capture session ID for multi-turn resume
      if (event.has("session_id") && !event.get("session_id").isJsonNull()) {
        sessionId = event.get("session_id").getAsString();
        log.info("Session ID: {}", sessionId);
      }

      String model = event.has("model") ? event.get("model").getAsString() : "unknown";
      log.info("Claude Code initialized — model: {}", model);

      // Log MCP server status
      if (event.has("mcp_servers") && event.get("mcp_servers").isJsonArray()) {
        for (JsonElement srv : event.getAsJsonArray("mcp_servers")) {
          if (srv.isJsonObject()) {
            JsonObject server = srv.getAsJsonObject();
            String name = server.has("name") ? server.get("name").getAsString() : "?";
            String status = server.has("status") ? server.get("status").getAsString() : "?";
            log.info("MCP server '{}': {}", name, status);
          }
        }
      }
    }
  }

  private void handleAssistantEvent(JsonObject event) {
    if (!event.has("message")) return;
    JsonObject message = event.getAsJsonObject("message");
    if (!message.has("content") || !message.get("content").isJsonArray()) return;

    for (JsonElement contentElem : message.getAsJsonArray("content")) {
      if (!contentElem.isJsonObject()) continue;
      JsonObject content = contentElem.getAsJsonObject();
      String contentType = content.has("type") ? content.get("type").getAsString() : "";

      if ("text".equals(contentType)) {
        String text = content.has("text") ? content.get("text").getAsString() : "";
        if (!text.isEmpty() && onTextChunk != null) {
          onTextChunk.accept(text);
        }
      } else if ("tool_use".equals(contentType)) {
        String toolName = content.has("name") ? content.get("name").getAsString() : "?";
        if (onToolStatus != null) {
          onToolStatus.accept("Using tool: " + toolName + "...");
        }
      }
    }
  }

  private void handleResultEvent(JsonObject event) {
    // Capture/update session ID from result
    if (event.has("session_id") && !event.get("session_id").isJsonNull()) {
      sessionId = event.get("session_id").getAsString();
    }

    boolean isError = event.has("is_error") && event.get("is_error").getAsBoolean();

    if (isError) {
      String errorText = "Unknown error";
      if (event.has("errors") && event.get("errors").isJsonArray()) {
        JsonArray errors = event.getAsJsonArray("errors");
        if (errors.size() > 0) {
          errorText = errors.get(0).getAsString();
        }
      } else if (event.has("result") && !event.get("result").isJsonNull()) {
        errorText = event.get("result").getAsString();
      }
      log.error("Claude Code error: {}", errorText);
      if (onTextChunk != null) {
        onTextChunk.accept("\nError: " + errorText + "\n");
      }
    }

    if (event.has("total_cost_usd")) {
      double cost = event.get("total_cost_usd").getAsDouble();
      log.info("Turn cost: ${}", String.format("%.4f", cost));
    }
  }

  private void resetSessionState() {
    sessionId = null;
    sessionContextFingerprint = null;
  }
}
