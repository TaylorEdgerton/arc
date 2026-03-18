package com.edgerton.arc.designer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SessionWelcomeSupportTest {

  @Test
  void welcomeMarkdownIncludesFormattedSessionTimestamp() {
    LocalDateTime startedAt = LocalDateTime.of(2026, 3, 17, 11, 7, 12);

    assertEquals(
        "## New session\n\nStarted: 2026-03-17 11:07:12",
        SessionWelcomeSupport.welcomeMarkdown(startedAt));
  }
}
