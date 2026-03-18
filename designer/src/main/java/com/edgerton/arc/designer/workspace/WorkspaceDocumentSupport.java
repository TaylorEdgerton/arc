package com.edgerton.arc.designer.workspace;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class WorkspaceDocumentSupport {

  private static final DateTimeFormatter TRANSCRIPT_TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

  private WorkspaceDocumentSupport() {}

  static Path workspaceDocumentHome(Path workspaceRoot) {
    return workspaceRoot;
  }

  static Path nextTranscriptPath(Path workspaceRoot, Clock clock) {
    String timestamp = TRANSCRIPT_TIMESTAMP_FORMATTER.format(LocalDateTime.now(clock));
    return workspaceDocumentHome(workspaceRoot).resolve("designer-chat-" + timestamp + ".md");
  }
}
