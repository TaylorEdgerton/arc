package com.edgerton.arc.designer;

import com.edgerton.arc.designer.bridge.NamedQueryBridge;
import com.edgerton.arc.designer.bridge.PerspectiveBridge;
import com.edgerton.arc.designer.bridge.TagBridge;
import com.edgerton.arc.designer.claude.ClaudeCodeManager;
import com.edgerton.arc.designer.claude.ClaudeCodeSetupChecker;
import com.edgerton.arc.designer.claude.PermissionPromptCoordinator;
import com.edgerton.arc.designer.mcp.McpToolServer;
import com.edgerton.arc.designer.ui.AiChatPanel;
import com.edgerton.arc.designer.ui.ChatWindow;
import com.edgerton.arc.designer.workspace.ProjectWorkspaceManager;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArcDesignerHook extends AbstractDesignerModuleHook {

  private static final Logger log = LoggerFactory.getLogger("Arc.Hook");

  private DesignerContext context;
  private AiChatPanel chatPanel;
  private ClaudeCodeManager claudeCodeManager;
  private McpToolServer mcpToolServer;

  @Override
  public void startup(DesignerContext context, LicenseState activationState) throws Exception {
    this.context = context;

    // Check if Claude Code CLI is installed
    ClaudeCodeSetupChecker checker = new ClaudeCodeSetupChecker();
    if (!checker.isAvailable()) {
      checker.showNotInstalledDialog();
      log.warn("Claude Code not available — AI Assistant will not start");
      return;
    }

    // Create workspace manager — local scratch dir for Claude Code
    ProjectWorkspaceManager workspaceManager = new ProjectWorkspaceManager(context);

    // Create the Perspective bridge for live view access
    PerspectiveBridge bridge = new PerspectiveBridge(context);

    // Create tag bridge for read-only tag access
    TagBridge tagBridge = new TagBridge(context);

    // Create named query bridge for read-only query access
    NamedQueryBridge namedQueryBridge = new NamedQueryBridge(context);

    PermissionPromptCoordinator permissionPromptCoordinator = new PermissionPromptCoordinator();

    // Start MCP server exposing bridge tools
    mcpToolServer =
        new McpToolServer(
            bridge, workspaceManager, tagBridge, namedQueryBridge, permissionPromptCoordinator);
    int mcpPort = mcpToolServer.start();

    // Start Claude Code process
    claudeCodeManager = new ClaudeCodeManager();
    claudeCodeManager.start(workspaceManager.getWorkingDirectory(), mcpPort);

    // Create UI
    ChatWindow chatWindow =
        new ChatWindow(claudeCodeManager, workspaceManager, permissionPromptCoordinator);
    chatPanel = new AiChatPanel(chatWindow);
    context.getDockingManager().addFrame(chatPanel);

    log.info(
        "AI Designer Assistant started — MCP port {}, Claude Code PID {}",
        mcpPort,
        claudeCodeManager.isRunning() ? "running" : "failed");
  }

  @Override
  public void shutdown() {
    if (claudeCodeManager != null) {
      claudeCodeManager.stop();
    }
    if (mcpToolServer != null) {
      mcpToolServer.stop();
    }
    this.context = null;
    this.chatPanel = null;
  }
}
