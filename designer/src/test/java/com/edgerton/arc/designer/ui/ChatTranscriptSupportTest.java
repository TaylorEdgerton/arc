package com.edgerton.arc.designer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatTranscriptSupportTest {

  @Test
  void toMarkdownFormatsFullConversationAndPreservesCodeBlocks() {
    List<ChatTranscriptSupport.TranscriptEntry> entries =
        List.of(
            new ChatTranscriptSupport.TranscriptEntry(
                ChatTranscriptSupport.EntryType.USER, "Please save this chat"),
            new ChatTranscriptSupport.TranscriptEntry(
                ChatTranscriptSupport.EntryType.STATUS, "MCP tool started"),
            new ChatTranscriptSupport.TranscriptEntry(
                ChatTranscriptSupport.EntryType.ASSISTANT,
                "Here is code:\n```java\nSystem.out.println(\"hi\");\n```\n"));

    assertEquals(
        "## User\n\n"
            + "Please save this chat\n\n"
            + "## Status\n\n"
            + "MCP tool started\n\n"
            + "## Assistant\n\n"
            + "Here is code:\n```java\nSystem.out.println(\"hi\");\n```\n\n",
        ChatTranscriptSupport.toMarkdown(entries));
  }
}
