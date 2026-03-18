package com.edgerton.arc.designer.ui;

import java.util.List;

final class ChatTranscriptSupport {

  private ChatTranscriptSupport() {}

  static String toMarkdown(List<TranscriptEntry> entries) {
    StringBuilder markdown = new StringBuilder();
    for (TranscriptEntry entry : entries) {
      markdown.append("## ").append(entry.type().displayName()).append("\n\n");
      markdown.append(entry.content() == null ? "" : entry.content().stripTrailing());
      markdown.append("\n\n");
    }
    return markdown.toString();
  }

  record TranscriptEntry(EntryType type, String content) {}

  enum EntryType {
    USER("User"),
    ASSISTANT("Assistant"),
    STATUS("Status");

    private final String displayName;

    EntryType(String displayName) {
      this.displayName = displayName;
    }

    String displayName() {
      return displayName;
    }
  }
}
