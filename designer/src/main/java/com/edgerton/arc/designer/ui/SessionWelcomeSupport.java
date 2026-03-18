package com.edgerton.arc.designer.ui;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class SessionWelcomeSupport {

  private static final DateTimeFormatter SESSION_TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private SessionWelcomeSupport() {}

  static String welcomeMarkdown(LocalDateTime sessionStartedAt) {
    return "## New session\n\nStarted: " + formatSessionStartedAt(sessionStartedAt);
  }

  static String formatSessionStartedAt(LocalDateTime sessionStartedAt) {
    return SESSION_TIMESTAMP_FORMATTER.format(sessionStartedAt);
  }
}
