package com.edgerton.arc.designer.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WorkspaceDocumentSupportTest {

  @Test
  void workspaceDocumentHomeUsesWorkspaceRoot() {
    Path workspaceRoot = Path.of("/tmp/project-workspace");

    assertEquals(workspaceRoot, WorkspaceDocumentSupport.workspaceDocumentHome(workspaceRoot));
  }

  @Test
  void nextTranscriptPathUsesTimestampSafeFileName() {
    Path workspaceRoot = Path.of("/tmp/project-workspace");
    Clock fixedClock = Clock.fixed(Instant.parse("2026-03-17T09:14:00Z"), ZoneOffset.UTC);

    assertEquals(
        workspaceRoot.resolve("designer-chat-2026-03-17T09-14-00.md"),
        WorkspaceDocumentSupport.nextTranscriptPath(workspaceRoot, fixedClock));
  }
}
