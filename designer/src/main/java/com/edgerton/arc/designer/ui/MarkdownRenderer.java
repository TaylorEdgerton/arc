package com.edgerton.arc.designer.ui;

import java.util.List;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Converts markdown text to HTML suitable for display in a JEditorPane. Uses commonmark-java for
 * CommonMark-spec parsing.
 */
public class MarkdownRenderer {

  private final Parser parser =
      Parser.builder().extensions(List.of(TablesExtension.create())).build();
  private final HtmlRenderer renderer =
      HtmlRenderer.builder().extensions(List.of(TablesExtension.create())).build();

  private static final String PAGE_CSS =
      "body { font-family: 'Segoe UI', Arial, sans-serif; font-size: 10pt; margin: 4px 8px; }"
          + "h1 { font-size: 16pt; margin: 8px 0 4px 0; }"
          + "h2 { font-size: 14pt; margin: 8px 0 4px 0; }"
          + "h3 { font-size: 12pt; margin: 6px 0 3px 0; }"
          + "h4, h5, h6 { font-size: 10pt; font-weight: bold; margin: 4px 0 2px 0; }"
          + "pre { background-color: #f0f0f0; font-family: 'Consolas', 'Courier New', monospace;"
          + "      font-size: 9pt; padding: 6px 8px; margin: 4px 0; }"
          + "code { font-family: 'Consolas', 'Courier New', monospace; font-size: 9pt;"
          + "       background-color: #f0f0f0; padding: 1px 3px; }"
          + "hr { border: none; border-top: 1px solid #cccccc; margin: 8px 0; }"
          + "blockquote { border-left: 3px solid #cccccc; margin: 4px 0; padding: 2px 8px;"
          + "             color: #555555; }"
          + ".user-msg { color: #333333; font-weight: bold; margin: 6px 0 2px 0; }"
          + ".tool-status { color: #888888; font-style: italic; font-size: 9pt; margin: 2px 0; }"
          + ".claude-msg { margin: 2px 0 6px 0; }"
          + ".permission-card { margin: 6px 0; padding: 8px 10px; border: 1px solid #d2b36c;"
          + "                   background-color: #fff8e8; }"
          + ".permission-title { font-weight: bold; color: #7a4f00; margin-bottom: 4px; }"
          + ".permission-target { font-family: 'Consolas', 'Courier New', monospace;"
          + "                     font-size: 9pt; color: #5b4b2b; margin-bottom: 4px; }"
          + ".permission-summary { margin-bottom: 6px; }"
          + ".permission-preview { background-color: #fffdf7; border: 1px solid #ead9b3;"
          + "                      padding: 6px 8px; margin: 4px 0 6px 0;"
          + "                      font-family: 'Consolas', 'Courier New', monospace;"
          + "                      font-size: 9pt; white-space: pre-wrap; }"
          + ".permission-actions { margin-top: 6px; }"
          + ".permission-actions table { width: auto; border: none; margin: 0; }"
          + ".permission-actions td { border: none; padding: 0 8px 0 0; background: transparent; }"
          + ".permission-chip { border: 1px solid #c6c6c6; background-color: #ffffff;"
          + "                   padding: 3px 8px; }"
          + ".permission-chip a { text-decoration: none; font-weight: bold; }"
          + ".permission-chip.approve { border-color: #7aa66f; background-color: #eef8eb; }"
          + ".permission-chip.approve a { color: #2f6d2f; }"
          + ".permission-chip.deny { border-color: #c98d8d; background-color: #fff0f0; }"
          + ".permission-chip.deny a { color: #8a2d2d; }"
          + "a { color: #0b61a4; text-decoration: none; }"
          + "a:hover { text-decoration: underline; }"
          + "table { border-width: 1px; border-style: solid; border-color: #cccccc; "
          + "        border-collapse: collapse; width: 100%; margin: 4px 0; }"
          + "th { border-width: 1px; border-style: solid; border-color: #cccccc; "
          + "     background-color: #e8e8e8; padding: 3px 6px; font-weight: bold; }"
          + "td { border-width: 1px; border-style: solid; border-color: #cccccc; "
          + "     padding: 3px 6px; }";

  /** Render a markdown string to an HTML fragment. */
  public String renderMarkdown(String markdown) {
    if (markdown == null || markdown.isEmpty()) {
      return "";
    }
    Node document = parser.parse(markdown);
    return renderer.render(document);
  }

  /** Wrap a user message as an HTML fragment. */
  public String renderUserMessage(String text) {
    return "<div class=\"user-msg\">You: " + escapeHtml(text) + "</div>";
  }

  /** Wrap a tool status as an HTML fragment. */
  public String renderToolStatus(String status) {
    return "<div class=\"tool-status\">[" + escapeHtml(status) + "]</div>";
  }

  /** Wrap a linked tool status as an HTML fragment. */
  public String renderLinkedToolStatus(String status, String href, String linkLabel) {
    return "<div class=\"tool-status\">["
        + escapeHtml(status)
        + ": <a href=\""
        + escapeHtml(href)
        + "\">"
        + escapeHtml(linkLabel)
        + "</a>]</div>";
  }

  /** Render an inline permission request card with approve/deny actions. */
  public String renderPermissionRequestCard(
      String requestId, String title, String targetPath, String summary, String preview) {
    return "<div class=\"permission-card\">"
        + "<div class=\"permission-title\">"
        + escapeHtml(title)
        + "</div>"
        + (targetPath == null || targetPath.isBlank()
            ? ""
            : "<div class=\"permission-target\">" + escapeHtml(targetPath) + "</div>")
        + "<div class=\"permission-summary\">"
        + escapeHtml(summary == null ? "" : summary)
        + "</div>"
        + "<div class=\"permission-preview\">"
        + escapeHtml(preview == null ? "" : preview)
        + "</div>"
        + "<div class=\"permission-actions\">"
        + "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
        + "<td><span class=\"permission-chip approve\"><a href=\""
        + escapeHtml(permissionActionHref(requestId, "approve"))
        + "\">Approve</a></span></td>"
        + "<td><span class=\"permission-chip deny\"><a href=\""
        + escapeHtml(permissionActionHref(requestId, "deny"))
        + "\">Deny</a></span></td>"
        + "</tr></table>"
        + "</div>"
        + "</div>";
  }

  /** Wrap a Claude response (already rendered HTML) as an HTML fragment. */
  public String wrapClaudeMessage(String bodyHtml) {
    return "<div class=\"claude-msg\">" + bodyHtml + "</div>";
  }

  /** Wrap the full conversation body in a complete HTML page with styles. */
  public String wrapPage(String bodyHtml) {
    return "<html><head><style>" + PAGE_CSS + "</style></head><body>" + bodyHtml + "</body></html>";
  }

  /** Escape text for safe embedding in HTML. */
  public static String escapeHtml(String text) {
    if (text == null) return "";
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private static String permissionActionHref(String requestId, String action) {
    return "https://claude.local/permission?id="
        + escapeHtml(requestId)
        + "&action="
        + escapeHtml(action);
  }
}
