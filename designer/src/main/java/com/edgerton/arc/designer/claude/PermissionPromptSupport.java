package com.edgerton.arc.designer.claude;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

final class PermissionPromptSupport {

  private static final int PREVIEW_LIMIT = 240;
  private static final Gson GSON = new Gson();
  private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

  private PermissionPromptSupport() {}

  static PermissionRequestDetails describeRequest(String toolName, JsonElement input) {
    JsonObject inputObject = input != null && input.isJsonObject() ? input.getAsJsonObject() : null;
    String filePath = getString(inputObject, "file_path");
    String displayPath = displayPath(filePath);

    return switch (toolName) {
      case "Write" ->
          new PermissionRequestDetails(
              "Write file",
              displayPath,
              previewContent(getString(inputObject, "content")),
              "Create or replace file contents");
      case "Edit" ->
          new PermissionRequestDetails(
              "Edit file",
              displayPath,
              previewContent(getString(inputObject, "new_string")),
              "Modify matching text in an existing file");
      case "MultiEdit" ->
          new PermissionRequestDetails(
              "Multi-edit file",
              displayPath,
              previewMultiEdit(inputObject),
              "Apply multiple edits to an existing file");
      case "Read" ->
          new PermissionRequestDetails(
              "Read file", displayPath, previewRead(inputObject), "Open file contents for Claude");
      default ->
          new PermissionRequestDetails(
              "Use tool: " + toolName,
              displayPath != null ? displayPath : "No file path provided",
              previewJson(input),
              "Permission required for this tool call");
    };
  }

  static String allowPayload(JsonElement input) {
    JsonObject payload = new JsonObject();
    payload.addProperty("behavior", "allow");
    payload.add("updatedInput", copyOf(input));
    return GSON.toJson(payload);
  }

  static String denyPayload(String message) {
    JsonObject payload = new JsonObject();
    payload.addProperty("behavior", "deny");
    payload.addProperty("message", message == null ? "Permission denied." : message);
    return GSON.toJson(payload);
  }

  static String approvalStatusMessage(
      boolean approved, String toolName, String targetPath, String message) {
    StringBuilder status = new StringBuilder();
    status
        .append("Permission ")
        .append(approved ? "approved" : "denied")
        .append(": ")
        .append(toolName);
    if (targetPath != null && !targetPath.isBlank()) {
      status.append(" ").append(targetPath);
    }
    if (!approved && message != null && !message.isBlank()) {
      status.append(" (").append(message).append(")");
    }
    return status.toString();
  }

  private static JsonElement copyOf(JsonElement input) {
    if (input == null || input.isJsonNull()) {
      return new JsonObject();
    }
    return input.deepCopy();
  }

  private static String previewRead(JsonObject inputObject) {
    if (inputObject == null) {
      return "Read the requested file.";
    }

    StringBuilder preview = new StringBuilder("Read the requested file");
    Integer offset = getInteger(inputObject, "offset");
    Integer limit = getInteger(inputObject, "limit");
    if (offset != null || limit != null) {
      preview.append(" (");
      if (offset != null) {
        preview.append("offset ").append(offset);
      }
      if (offset != null && limit != null) {
        preview.append(", ");
      }
      if (limit != null) {
        preview.append("limit ").append(limit);
      }
      preview.append(")");
    }
    preview.append(".");
    return preview.toString();
  }

  private static String previewMultiEdit(JsonObject inputObject) {
    if (inputObject == null
        || !inputObject.has("edits")
        || !inputObject.get("edits").isJsonArray()) {
      return "Apply multiple edits to the requested file.";
    }

    JsonArray edits = inputObject.getAsJsonArray("edits");
    if (edits.isEmpty()) {
      return "Apply multiple edits to the requested file.";
    }

    JsonElement first = edits.get(0);
    String firstPreview =
        first.isJsonObject()
            ? previewContent(getString(first.getAsJsonObject(), "new_string"))
            : truncate(PRETTY_GSON.toJson(first));
    if (edits.size() == 1) {
      return "1 edit.\n\nPreview:\n" + firstPreview;
    }
    return edits.size() + " edits.\n\nFirst edit preview:\n" + firstPreview;
  }

  private static String previewJson(JsonElement input) {
    if (input == null || input.isJsonNull()) {
      return "No additional input provided.";
    }
    return truncate(PRETTY_GSON.toJson(input));
  }

  private static String previewContent(String content) {
    if (content == null || content.isBlank()) {
      return "No content preview available.";
    }
    return truncate(content);
  }

  private static String truncate(String text) {
    if (text == null) {
      return "No preview available.";
    }
    if (text.length() <= PREVIEW_LIMIT) {
      return text;
    }
    return text.substring(0, PREVIEW_LIMIT) + "\n... [truncated]";
  }

  private static String displayPath(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      return null;
    }

    try {
      Path normalized = Path.of(rawPath).toAbsolutePath().normalize();
      Path userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
      if (normalized.startsWith(userHome)) {
        Path relative = userHome.relativize(normalized);
        return relative.getNameCount() == 0 ? "~" : "~/" + relative.toString().replace('\\', '/');
      }
      return normalized.toString();
    } catch (InvalidPathException e) {
      return rawPath;
    }
  }

  private static String getString(JsonObject object, String key) {
    if (object == null || !object.has(key)) {
      return null;
    }

    JsonElement value = object.get(key);
    if (value == null || value.isJsonNull()) {
      return null;
    }
    if (value.isJsonPrimitive() && ((JsonPrimitive) value).isString()) {
      return value.getAsString();
    }
    return value.toString();
  }

  private static Integer getInteger(JsonObject object, String key) {
    if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
      return null;
    }

    try {
      return object.get(key).getAsInt();
    } catch (Exception e) {
      return null;
    }
  }

  record PermissionRequestDetails(
      String title, String targetPath, String preview, String summary) {}
}
