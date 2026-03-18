package com.edgerton.arc.designer.ui;

import com.edgerton.arc.designer.claude.ClaudeCodeManager;
import com.edgerton.arc.designer.claude.PermissionPromptCoordinator;
import com.edgerton.arc.designer.workspace.ProjectWorkspaceManager;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;

/**
 * Swing chat panel that pipes user input to Claude Code and displays streamed output with rich
 * markdown rendering via JEditorPane.
 */
public class ChatWindow extends JPanel {

  private static final String ACTION_LINK_HOST = "claude.local";

  private final JEditorPane conversationPane;
  private final JTextField inputField;
  private final JButton sendButton;
  private final JButton cancelButton;
  private final JButton saveButton;
  private final JButton newSessionButton;
  private final JLabel statusLabel;
  private final ClaudeCodeManager claudeCodeManager;
  private final ProjectWorkspaceManager workspaceManager;
  private final PermissionPromptCoordinator permissionPromptCoordinator;
  private final Clock clock;
  private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();

  /** Completed turn HTML fragments (user messages, finalized Claude responses, tool statuses). */
  private final List<String> completedTurns = new ArrayList<>();

  /** Structured transcript entries used when saving the current chat to Markdown. */
  private final List<ChatTranscriptSupport.TranscriptEntry> transcriptEntries = new ArrayList<>();

  /** Accumulates raw markdown for the current in-progress Claude response. */
  private final StringBuilder currentTurnMarkdown = new StringBuilder();

  /** Accumulates tool status lines for the current turn (shown above the Claude response). */
  private final List<String> currentTurnToolStatuses = new ArrayList<>();

  /** Pending permission requests that need an inline approve/deny decision. */
  private final List<PermissionPromptCoordinator.PermissionRequest> pendingPermissionRequests =
      new ArrayList<>();

  /** Whether the current Claude turn has started emitting text. */
  private boolean turnHasText = false;

  /** Whether the current turn has already been finalized (guards against double-finalize). */
  private boolean turnFinalized = true;

  private LocalDateTime sessionStartedAt;

  public ChatWindow(
      ClaudeCodeManager claudeCodeManager,
      ProjectWorkspaceManager workspaceManager,
      PermissionPromptCoordinator permissionPromptCoordinator) {
    this(
        claudeCodeManager,
        workspaceManager,
        permissionPromptCoordinator,
        Clock.systemDefaultZone());
  }

  ChatWindow(
      ClaudeCodeManager claudeCodeManager,
      ProjectWorkspaceManager workspaceManager,
      PermissionPromptCoordinator permissionPromptCoordinator,
      Clock clock) {
    super(new BorderLayout());
    this.claudeCodeManager = claudeCodeManager;
    this.workspaceManager = workspaceManager;
    this.permissionPromptCoordinator = permissionPromptCoordinator;
    this.clock = clock;
    this.sessionStartedAt = LocalDateTime.now(clock);

    // North — toolbar
    JPanel toolbar = new JPanel(new BorderLayout(4, 0));
    toolbar.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

    JLabel projectLabel = new JLabel(workspaceManager.getProjectName());
    projectLabel.setFont(projectLabel.getFont().deriveFont(Font.BOLD));
    toolbar.add(projectLabel, BorderLayout.WEST);

    JPanel toolbarRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
    statusLabel = new JLabel();
    updateStatus();
    toolbarRight.add(statusLabel);

    saveButton = new JButton("Save Chat");
    saveButton.setToolTipText("Save the current conversation as Markdown in the workspace root");
    saveButton.setEnabled(false);
    saveButton.addActionListener(e -> handleSaveChat());
    toolbarRight.add(saveButton);

    newSessionButton = new JButton("New Session");
    newSessionButton.setToolTipText("Save or clear the current session and start fresh");
    newSessionButton.addActionListener(e -> handleNewSession());
    toolbarRight.add(newSessionButton);

    Icon folderIcon = resolveFolderIcon();
    JButton folderButton = folderIcon != null ? new JButton(folderIcon) : new JButton("Open");
    folderButton.setToolTipText("Open workspace folder");
    folderButton.setMargin(new Insets(2, 4, 2, 4));
    if (folderIcon != null) {
      folderButton.setText(null);
      folderButton.setPreferredSize(new Dimension(30, 26));
    }
    folderButton.addActionListener(e -> workspaceManager.openInFileBrowser());
    toolbarRight.add(folderButton);
    toolbar.add(toolbarRight, BorderLayout.EAST);

    add(toolbar, BorderLayout.NORTH);

    // Center — conversation history (rich HTML)
    conversationPane = new JEditorPane();
    conversationPane.setContentType("text/html");
    conversationPane.setEditable(false);
    conversationPane.addHyperlinkListener(this::handleConversationLink);
    JScrollPane scrollPane = new JScrollPane(conversationPane);
    add(scrollPane, BorderLayout.CENTER);

    // South — input row with send + cancel buttons
    JPanel inputPanel = new JPanel(new BorderLayout(4, 0));
    inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    inputField = new JTextField();
    inputField.addActionListener(e -> handleSend());
    inputPanel.add(inputField, BorderLayout.CENTER);

    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
    buttonPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

    cancelButton = new JButton("Cancel");
    cancelButton.setToolTipText("Cancel the current response");
    cancelButton.setVisible(false);
    cancelButton.addActionListener(e -> handleCancel());
    buttonPanel.add(cancelButton);

    sendButton = new JButton("Send");
    sendButton.addActionListener(e -> handleSend());
    buttonPanel.add(sendButton);

    inputPanel.add(buttonPanel, BorderLayout.EAST);
    add(inputPanel, BorderLayout.SOUTH);

    // Wire up Claude Code callbacks
    claudeCodeManager.setOnTextChunk(this::onClaudeText);
    claudeCodeManager.setOnToolStatus(this::onToolStatus);
    claudeCodeManager.setOnTurnComplete(this::onTurnComplete);
    permissionPromptCoordinator.setOnRequest(this::onPermissionRequest);
    permissionPromptCoordinator.setOnResolution(this::onPermissionResolved);

    updateActionState();
    refreshConversation();
  }

  private void handleSend() {
    String message = inputField.getText().trim();
    if (message.isEmpty() || !claudeCodeManager.isRunning() || claudeCodeManager.isBusy()) {
      return;
    }

    ProjectWorkspaceManager.ClaudeContextSnapshot contextSnapshot;
    try {
      contextSnapshot = workspaceManager.prepareClaudeContext();
    } catch (IOException e) {
      completedTurns.add(
          markdownRenderer.renderToolStatus(
              "Failed to prepare Claude instructions: " + e.getMessage()));
      transcriptEntries.add(
          new ChatTranscriptSupport.TranscriptEntry(
              ChatTranscriptSupport.EntryType.STATUS,
              "Failed to prepare Claude instructions: " + e.getMessage()));
      refreshConversation();
      return;
    }

    completedTurns.add(markdownRenderer.renderUserMessage(message));
    transcriptEntries.add(
        new ChatTranscriptSupport.TranscriptEntry(ChatTranscriptSupport.EntryType.USER, message));
    refreshConversation();

    inputField.setText("");
    sendButton.setEnabled(false);
    sendButton.setText("Thinking...");
    cancelButton.setVisible(true);

    currentTurnMarkdown.setLength(0);
    currentTurnToolStatuses.clear();
    turnHasText = false;
    turnFinalized = false;

    claudeCodeManager.sendMessage(message, contextSnapshot.fingerprint());
    updateActionState();
  }

  private void handleCancel() {
    permissionPromptCoordinator.cancelPendingRequests("Request canceled by user.");
    claudeCodeManager.cancelCurrentTurn();
    finalizeTurn();
  }

  private void handleNewSession() {
    if (claudeCodeManager.isBusy()) {
      return;
    }

    int choice =
        JOptionPane.showConfirmDialog(
            this,
            "Save this session before clearing?",
            "New Session",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE);
    if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
      return;
    }

    if (choice == JOptionPane.YES_OPTION && !saveCurrentTranscript(false, true)) {
      return;
    }

    try {
      workspaceManager.prepareClaudeContext();
    } catch (IOException e) {
      showNewSessionError("Failed to refresh Claude context: " + e.getMessage());
      return;
    }

    claudeCodeManager.resetSession();
    startNewSession();
  }

  private void handleSaveChat() {
    saveCurrentTranscript(true, false);
  }

  private void onClaudeText(String text) {
    SwingUtilities.invokeLater(
        () -> {
          turnHasText = true;
          currentTurnMarkdown.append(text);
          refreshConversation();
        });
  }

  private void onToolStatus(String status) {
    SwingUtilities.invokeLater(
        () -> {
          currentTurnToolStatuses.add(status);
          refreshConversation();
        });
  }

  private void onTurnComplete() {
    SwingUtilities.invokeLater(
        () -> {
          permissionPromptCoordinator.cancelPendingRequests("Permission request no longer active.");
          finalizeTurn();
        });
  }

  private void onPermissionRequest(PermissionPromptCoordinator.PermissionRequest request) {
    SwingUtilities.invokeLater(
        () -> {
          pendingPermissionRequests.add(request);
          refreshConversation();
        });
  }

  private void onPermissionResolved(
      PermissionPromptCoordinator.ResolvedPermissionRequest resolvedRequest) {
    SwingUtilities.invokeLater(
        () -> {
          pendingPermissionRequests.removeIf(
              request -> request.requestId().equals(resolvedRequest.request().requestId()));
          completedTurns.add(markdownRenderer.renderToolStatus(resolvedRequest.statusMessage()));
          transcriptEntries.add(
              new ChatTranscriptSupport.TranscriptEntry(
                  ChatTranscriptSupport.EntryType.STATUS, resolvedRequest.statusMessage()));
          refreshConversation();
        });
  }

  /** Finalize the current turn — move accumulated content to completedTurns and reset UI. */
  private void finalizeTurn() {
    if (turnFinalized) return;
    turnFinalized = true;

    for (String status : currentTurnToolStatuses) {
      completedTurns.add(markdownRenderer.renderToolStatus(status));
      transcriptEntries.add(
          new ChatTranscriptSupport.TranscriptEntry(
              ChatTranscriptSupport.EntryType.STATUS, status));
    }

    if (turnHasText && currentTurnMarkdown.length() > 0) {
      String rawMarkdown = currentTurnMarkdown.toString();
      completedTurns.add(
          markdownRenderer.wrapClaudeMessage(markdownRenderer.renderMarkdown(rawMarkdown)));
      transcriptEntries.add(
          new ChatTranscriptSupport.TranscriptEntry(
              ChatTranscriptSupport.EntryType.ASSISTANT, rawMarkdown));
    }

    currentTurnMarkdown.setLength(0);
    currentTurnToolStatuses.clear();
    turnHasText = false;

    sendButton.setEnabled(true);
    sendButton.setText("Send");
    cancelButton.setVisible(false);
    updateActionState();

    refreshConversation();
  }

  private void handleConversationLink(HyperlinkEvent event) {
    if (event.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
      return;
    }

    try {
      URI uri = resolveConversationLink(event);
      if (uri == null) {
        return;
      }
      if (isPermissionAction(uri)) {
        handlePermissionAction(uri);
      } else if ("file".equalsIgnoreCase(uri.getScheme())) {
        workspaceManager.openPath(Path.of(uri));
      } else if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().browse(uri);
      }
    } catch (Exception e) {
      completedTurns.add(
          markdownRenderer.renderToolStatus("Failed to open link: " + e.getMessage()));
      transcriptEntries.add(
          new ChatTranscriptSupport.TranscriptEntry(
              ChatTranscriptSupport.EntryType.STATUS, "Failed to open link: " + e.getMessage()));
      refreshConversation();
    }
  }

  /** Rebuild the full HTML page from completed turns + in-progress turn and set it. */
  private void refreshConversation() {
    StringBuilder body = new StringBuilder();

    for (String turn : completedTurns) {
      body.append(turn);
    }

    for (String status : currentTurnToolStatuses) {
      body.append(markdownRenderer.renderToolStatus(status));
    }

    for (PermissionPromptCoordinator.PermissionRequest request : pendingPermissionRequests) {
      body.append(
          markdownRenderer.renderPermissionRequestCard(
              request.requestId(),
              request.title(),
              request.targetPath(),
              request.summary(),
              request.preview()));
    }

    if (turnHasText && currentTurnMarkdown.length() > 0) {
      body.append(
          markdownRenderer.wrapClaudeMessage(
              markdownRenderer.renderMarkdown(currentTurnMarkdown.toString())));
    } else if (completedTurns.isEmpty()
        && currentTurnToolStatuses.isEmpty()
        && pendingPermissionRequests.isEmpty()) {
      body.append(
          markdownRenderer.wrapClaudeMessage(
              markdownRenderer.renderMarkdown(
                  SessionWelcomeSupport.welcomeMarkdown(sessionStartedAt))));
    }

    conversationPane.setText(markdownRenderer.wrapPage(body.toString()));

    SwingUtilities.invokeLater(
        () -> conversationPane.setCaretPosition(conversationPane.getDocument().getLength()));
  }

  private void updateStatus() {
    SwingUtilities.invokeLater(
        () -> {
          if (claudeCodeManager.isRunning()) {
            statusLabel.setText("\u2022 Running");
            statusLabel.setForeground(new Color(0, 150, 0));
          } else {
            statusLabel.setText("\u2022 Not running");
            statusLabel.setForeground(Color.RED);
          }
        });
  }

  private boolean saveCurrentTranscript(
      boolean announceInConversation, boolean showDialogOnFailure) {
    if (transcriptEntries.isEmpty()) {
      return true;
    }

    try {
      String transcriptMarkdown = ChatTranscriptSupport.toMarkdown(transcriptEntries);
      ProjectWorkspaceManager.SavedDocument savedDocument =
          workspaceManager.saveChatTranscript(transcriptMarkdown);
      if (announceInConversation) {
        completedTurns.add(
            markdownRenderer.renderLinkedToolStatus(
                "Saved chat transcript",
                savedDocument.path().toUri().toString(),
                savedDocument.displayPath()));
        transcriptEntries.add(
            new ChatTranscriptSupport.TranscriptEntry(
                ChatTranscriptSupport.EntryType.STATUS,
                "Saved chat transcript: " + savedDocument.displayPath()));
        refreshConversation();
      }
      return true;
    } catch (IOException e) {
      String message = "Failed to save chat transcript: " + e.getMessage();
      completedTurns.add(markdownRenderer.renderToolStatus(message));
      transcriptEntries.add(
          new ChatTranscriptSupport.TranscriptEntry(
              ChatTranscriptSupport.EntryType.STATUS, message));
      refreshConversation();
      if (showDialogOnFailure) {
        showNewSessionError(message);
      }
      return false;
    }
  }

  private void startNewSession() {
    completedTurns.clear();
    transcriptEntries.clear();
    currentTurnMarkdown.setLength(0);
    currentTurnToolStatuses.clear();
    pendingPermissionRequests.clear();
    turnHasText = false;
    turnFinalized = true;
    inputField.setText("");
    sendButton.setEnabled(true);
    sendButton.setText("Send");
    cancelButton.setVisible(false);
    sessionStartedAt = LocalDateTime.now(clock);
    updateActionState();
    refreshConversation();
  }

  private void showNewSessionError(String message) {
    JOptionPane.showMessageDialog(this, message, "New Session", JOptionPane.ERROR_MESSAGE);
  }

  private void updateActionState() {
    saveButton.setEnabled(!claudeCodeManager.isBusy() && !transcriptEntries.isEmpty());
    newSessionButton.setEnabled(!claudeCodeManager.isBusy());
  }

  private URI resolveConversationLink(HyperlinkEvent event) {
    if (event.getURL() != null) {
      return URI.create(event.getURL().toExternalForm());
    }
    if (event.getDescription() != null && !event.getDescription().isBlank()) {
      return URI.create(event.getDescription());
    }
    return null;
  }

  private boolean isPermissionAction(URI uri) {
    return ACTION_LINK_HOST.equalsIgnoreCase(uri.getHost())
        && "/permission".equalsIgnoreCase(uri.getPath());
  }

  private void handlePermissionAction(URI uri) {
    String query = uri.getQuery();
    if (query == null || query.isBlank()) {
      return;
    }

    String requestId = null;
    String action = null;
    for (String pair : query.split("&")) {
      String[] keyValue = pair.split("=", 2);
      if (keyValue.length != 2) {
        continue;
      }
      if ("id".equalsIgnoreCase(keyValue[0])) {
        requestId = keyValue[1];
      } else if ("action".equalsIgnoreCase(keyValue[0])) {
        action = keyValue[1];
      }
    }

    if (requestId == null || action == null) {
      return;
    }

    boolean handled =
        "approve".equalsIgnoreCase(action)
            ? permissionPromptCoordinator.approveRequest(requestId)
            : permissionPromptCoordinator.denyRequest(requestId, "Denied by user.");
    if (!handled) {
      String message = "Permission request no longer available.";
      completedTurns.add(markdownRenderer.renderToolStatus(message));
      transcriptEntries.add(
          new ChatTranscriptSupport.TranscriptEntry(
              ChatTranscriptSupport.EntryType.STATUS, message));
      refreshConversation();
    }
  }

  private Icon resolveFolderIcon() {
    Icon icon = UIManager.getIcon("FileView.directoryIcon");
    if (icon == null) {
      icon = UIManager.getIcon("Tree.closedIcon");
    }
    return icon;
  }
}
