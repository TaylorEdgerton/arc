package com.edgerton.arc.designer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarkdownRendererTest {

  private final MarkdownRenderer renderer = new MarkdownRenderer();

  @Test
  void escapeHtmlEscapesSpecialCharactersAndNull() {
    assertEquals("", MarkdownRenderer.escapeHtml(null));
    assertEquals(
        "A&amp;B &lt;tag&gt; &quot;quoted&quot;",
        MarkdownRenderer.escapeHtml("A&B <tag> \"quoted\""));
  }

  @Test
  void renderMarkdownSupportsCommonFormatting() {
    String html = renderer.renderMarkdown("**Bold**\n\n| A | B |\n| - | - |\n| 1 | 2 |");

    assertTrue(html.contains("<strong>Bold</strong>"));
    assertTrue(html.contains("<table>"));
    assertTrue(html.contains("<td>1</td>"));
    assertTrue(html.contains("<td>2</td>"));
  }

  @Test
  void renderPermissionRequestCardIncludesActionsAndPreview() {
    String html =
        renderer.renderPermissionRequestCard(
            "request-123", "Write file", "~/project/output.py", "Create file contents", "print(1)");

    assertTrue(html.contains("Approve"));
    assertTrue(html.contains("Deny"));
    assertTrue(html.contains("request-123"));
    assertTrue(html.contains("output.py"));
    assertTrue(html.contains("print(1)"));
  }
}
