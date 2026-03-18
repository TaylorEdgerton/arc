package com.edgerton.arc.designer.mcp;

import com.edgerton.arc.designer.bridge.PerspectiveBridge;
import com.edgerton.arc.designer.bridge.TagBridge;
import com.edgerton.arc.designer.claude.ClaudeCodeManager;
import com.edgerton.arc.designer.claude.PermissionPromptCoordinator;
import com.edgerton.arc.designer.workspace.ProjectWorkspaceManager;
import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A local MCP server implementing Streamable HTTP transport (JSON-RPC 2.0 over HTTP POST). Exposes
 * PerspectiveBridge methods as MCP tools that Claude Code can call.
 *
 * <p>Protocol: POST /mcp with JSON-RPC 2.0 request body, returns JSON-RPC 2.0 response. Claude Code
 * connects via --mcp-config with type "http".
 */
public class McpToolServer {

  private static final Logger log = LoggerFactory.getLogger("AI-Designer.McpServer");
  private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
  private static final String SERVER_NAME = "ignition-designer-mcp";
  private static final String SERVER_VERSION = "0.1.0";

  private final PerspectiveBridge bridge;
  private final ProjectWorkspaceManager workspaceManager;
  private final TagBridge tagBridge;
  private final PermissionPromptCoordinator permissionPromptCoordinator;
  private HttpServer server;
  private int port;

  public McpToolServer(
      PerspectiveBridge bridge,
      ProjectWorkspaceManager workspaceManager,
      TagBridge tagBridge,
      PermissionPromptCoordinator permissionPromptCoordinator) {
    this.bridge = bridge;
    this.workspaceManager = workspaceManager;
    this.tagBridge = tagBridge;
    this.permissionPromptCoordinator = permissionPromptCoordinator;
  }

  public int start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newFixedThreadPool(4));
    server.createContext("/mcp", this::handleMcp);
    server.start();
    port = server.getAddress().getPort();
    log.info("MCP server started on port {}", port);
    return port;
  }

  public void stop() {
    if (server != null) {
      server.stop(2);
      log.info("MCP server stopped");
    }
  }

  public int getPort() {
    return port;
  }

  private void handleMcp(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      // GET requests for SSE — not needed for Streamable HTTP, return 405
      sendResponse(exchange, 405, "Method Not Allowed");
      return;
    }

    String body;
    try (InputStream is = exchange.getRequestBody()) {
      body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    log.debug("MCP request: {}", body);

    try {
      JsonObject request = JsonParser.parseString(body).getAsJsonObject();
      String method = request.has("method") ? request.get("method").getAsString() : "";
      JsonElement id = request.get("id");

      JsonObject response;
      switch (method) {
        case "initialize":
          response = handleInitialize(id);
          break;
        case "notifications/initialized":
        case "initialized":
          // Notification — no response needed, but send empty OK
          sendResponse(exchange, 200, "");
          return;
        case "tools/list":
          response = handleToolsList(id);
          break;
        case "tools/call":
          JsonObject params = request.getAsJsonObject("params");
          response = handleToolsCall(id, params);
          break;
        case "ping":
          response = jsonRpcResult(id, new JsonObject());
          break;
        default:
          response = jsonRpcError(id, -32601, "Method not found: " + method);
          break;
      }

      String responseStr = response.toString();
      log.debug("MCP response: {}", responseStr);
      sendResponse(exchange, 200, responseStr);

    } catch (Exception e) {
      log.error("MCP request handling failed", e);
      JsonObject error = jsonRpcError(null, -32603, "Internal error: " + e.getMessage());
      sendResponse(exchange, 200, error.toString());
    }
  }

  private JsonObject handleInitialize(JsonElement id) {
    JsonObject result = new JsonObject();
    result.addProperty("protocolVersion", MCP_PROTOCOL_VERSION);

    JsonObject capabilities = new JsonObject();
    JsonObject tools = new JsonObject();
    capabilities.add("tools", tools);
    result.add("capabilities", capabilities);

    JsonObject serverInfo = new JsonObject();
    serverInfo.addProperty("name", SERVER_NAME);
    serverInfo.addProperty("version", SERVER_VERSION);
    result.add("serverInfo", serverInfo);

    return jsonRpcResult(id, result);
  }

  private JsonObject handleToolsList(JsonElement id) {
    JsonArray toolsList = new JsonArray();

    toolsList.add(
        defineTool(
            "read_view",
            "Read a Perspective view's full configuration including the component tree "
                + "and an INDEX PATH MAP showing numeric paths (e.g., D.0 = root, D.0:0 = first child). "
                + "If view_path is omitted, reads the currently open view. "
                + "If view_path is provided (from list_views), reads that view without opening it.",
            optionalString(
                "view_path",
                "Resource path from list_views. Omit to read the currently open view."),
            new String[0]));

    toolsList.add(
        defineTool(
            "read_component",
            "Read detailed properties of a specific component.",
            requiredString("path", "Numeric index path from read_view, e.g. 'D.0:1:2'")));

    // --- Write/mutate tools disabled for read-only release (US-007, US-009) ---
    // toolsList.add(defineTool("write_property", ...));
    // toolsList.add(defineTool("add_binding", ...));
    // toolsList.add(defineTool("add_component", ...));
    // toolsList.add(defineTool("delete_component", ...));

    toolsList.add(
        defineTool(
            "select_component",
            "Highlight a component on the Designer canvas.",
            requiredString("path", "Numeric index path of component to select, e.g. 'D.0:0'")));

    toolsList.add(
        defineTool(
            "list_views",
            "List all Perspective view resource paths in the project.",
            new JsonObject()));

    toolsList.add(
        defineTool(
            "get_open_view_name",
            "Get the resource path of the currently open Perspective view.",
            new JsonObject()));

    toolsList.add(
        defineTool(
            "open_folder",
            "Open the local project workspace folder in the OS file browser.",
            new JsonObject()));

    toolsList.add(
        defineTool(
            "list_scripts",
            "List all Python script resources in the project, grouped by type "
                + "(script-python = project library scripts, event-scripts = gateway event scripts).",
            new JsonObject()));

    toolsList.add(
        defineTool(
            "read_script",
            "Read the Python source code of a script resource. "
                + "Accepts a full path from list_scripts (e.g. 'ignition/script-python/myPkg/myModule') "
                + "or a bare path (e.g. 'myPkg/myModule').",
            requiredString("script_path", "Script resource path from list_scripts")));

    toolsList.add(permissionPromptToolDefinition());

    // --- Tag read tools (US-019) ---
    toolsList.add(
        defineTool(
            "browse_tags",
            "Browse tag folders and list child tags at a given path. "
                + "Returns name, fullPath, tagType, dataType, and hasChildren for each entry. "
                + "Use provider prefixes like [default] or [System]. "
                + "Set depth > 0 to recurse into subfolders (0 = direct children only, -1 = full tree).",
            mergeProperties(
                requiredString(
                    "path", "Tag path to browse, e.g. '[default]' or '[default]Folder1'"),
                optionalInteger(
                    "depth",
                    "Recursion depth: 0 = direct children (default), 1+ = levels deep, -1 = full tree"),
                optionalInteger("limit", "Max results to return (default 500, max 2000)")),
            new String[] {"path"}));

    toolsList.add(
        defineTool(
            "read_tag_values",
            "Read current value, quality, and timestamp for one or more tags. "
                + "Accepts full tag paths including provider prefix.",
            requiredStringArray(
                "paths", "Array of tag paths, e.g. ['[default]Tag1', '[default]Folder/Tag2']")));

    toolsList.add(
        defineTool(
            "read_tag_configuration",
            "Read full tag configuration (data type, value source, OPC settings, alarms, history, "
                + "scaling, engineering units, etc.) for one or more tags. "
                + "For UDT definitions, use _types_ paths like '[default]_types_/Motor'. "
                + "Set recursive=true to include child tag configurations.",
            mergeProperties(
                requiredStringArray("paths", "Array of tag paths to read configuration for"),
                optionalBoolean("recursive", "Include child tag configurations (default false)")),
            new String[] {"paths"}));

    JsonObject result = new JsonObject();
    result.add("tools", toolsList);
    return jsonRpcResult(id, result);
  }

  private JsonObject handleToolsCall(JsonElement id, JsonObject params) {
    String toolName = params.get("name").getAsString();
    JsonObject arguments =
        params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

    log.info("Tool call: {} with args: {}", toolName, arguments);

    String resultText;
    boolean isError = false;

    try {
      switch (toolName) {
        case "read_view":
          String viewPath = getStr(arguments, "view_path");
          if (viewPath != null && !viewPath.isEmpty()) {
            resultText = bridge.readViewResource(viewPath);
          } else {
            resultText = bridge.getCurrentViewConfig();
          }
          break;
        case "read_component":
          resultText = bridge.getComponentDetails(getStr(arguments, "path"));
          break;
        // --- Write/mutate dispatch disabled for read-only release ---
        // case "write_property": ...
        // case "add_binding": ...
        // case "add_component": ...
        // case "delete_component": ...
        case "select_component":
          resultText = bridge.setSelection(getStr(arguments, "path"));
          break;
        case "list_views":
          JsonArray viewList = new JsonArray();
          for (String v : bridge.getAvailableViews()) {
            viewList.add(v);
          }
          resultText = viewList.toString();
          break;
        case "get_open_view_name":
          resultText = bridge.getOpenViewName();
          break;
        case "open_folder":
          workspaceManager.openInFileBrowser();
          resultText = "Opened folder: " + workspaceManager.getWorkingDirectory().getAbsolutePath();
          break;
        case "list_scripts":
          resultText = bridge.listScripts();
          break;
        case "read_script":
          resultText = bridge.readScript(getStr(arguments, "script_path"));
          break;
        case ClaudeCodeManager.PERMISSION_PROMPT_TOOL_NAME:
          resultText =
              permissionPromptCoordinator.requestPermission(
                  getStr(arguments, "tool_name"), arguments.get("input"));
          break;
        case "browse_tags":
          resultText =
              tagBridge.browseTags(
                  getStr(arguments, "path"),
                  getInt(arguments, "depth", 0),
                  getInt(arguments, "limit", 0));
          break;
        case "read_tag_values":
          resultText = tagBridge.readTagValues(getStringList(arguments, "paths"));
          break;
        case "read_tag_configuration":
          resultText =
              tagBridge.readTagConfiguration(
                  getStringList(arguments, "paths"), getBool(arguments, "recursive", false));
          break;
        default:
          resultText = "Unknown tool: " + toolName;
          isError = true;
          break;
      }
    } catch (Exception e) {
      log.error("Tool call '{}' failed", toolName, e);
      resultText = "Error executing " + toolName + ": " + e.getMessage();
      isError = true;
    }

    // Check if the result itself indicates an error
    if (resultText != null && resultText.startsWith("{\"error\"")) {
      isError = true;
    }

    JsonObject result = new JsonObject();
    JsonArray content = new JsonArray();
    JsonObject textContent = new JsonObject();
    textContent.addProperty("type", "text");
    textContent.addProperty("text", resultText != null ? resultText : "null");
    content.add(textContent);
    result.add("content", content);
    result.addProperty("isError", isError);

    return jsonRpcResult(id, result);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  private String getStr(JsonObject obj, String key) {
    if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
    JsonElement val = obj.get(key);
    // If the value is an object or array, return it as JSON string
    if (val.isJsonObject() || val.isJsonArray()) {
      return val.toString();
    }
    return val.getAsString();
  }

  private int getInt(JsonObject obj, String key, int defaultValue) {
    if (!obj.has(key) || obj.get(key).isJsonNull()) return defaultValue;
    try {
      return obj.get(key).getAsInt();
    } catch (Exception e) {
      return defaultValue;
    }
  }

  private boolean getBool(JsonObject obj, String key, boolean defaultValue) {
    if (!obj.has(key) || obj.get(key).isJsonNull()) return defaultValue;
    try {
      return obj.get(key).getAsBoolean();
    } catch (Exception e) {
      return defaultValue;
    }
  }

  private java.util.List<String> getStringList(JsonObject obj, String key) {
    java.util.List<String> result = new java.util.ArrayList<>();
    if (!obj.has(key) || obj.get(key).isJsonNull()) return result;
    JsonElement val = obj.get(key);
    if (val.isJsonArray()) {
      for (JsonElement el : val.getAsJsonArray()) {
        result.add(el.getAsString());
      }
    } else if (val.isJsonPrimitive()) {
      // Single string passed instead of array
      result.add(val.getAsString());
    }
    return result;
  }

  private JsonObject defineTool(String name, String description, JsonObject inputProperties) {
    JsonObject tool = new JsonObject();
    tool.addProperty("name", name);
    tool.addProperty("description", description);

    JsonObject inputSchema = new JsonObject();
    inputSchema.addProperty("type", "object");
    inputSchema.add("properties", inputProperties);

    // Collect required fields
    JsonArray required = new JsonArray();
    for (String key : inputProperties.keySet()) {
      required.add(key);
    }
    if (required.size() > 0) {
      inputSchema.add("required", required);
    }

    tool.add("inputSchema", inputSchema);
    return tool;
  }

  private JsonObject defineTool(
      String name, String description, JsonObject inputProperties, String... requiredFields) {
    JsonObject tool = new JsonObject();
    tool.addProperty("name", name);
    tool.addProperty("description", description);

    JsonObject inputSchema = new JsonObject();
    inputSchema.addProperty("type", "object");
    inputSchema.add("properties", inputProperties);

    JsonArray required = new JsonArray();
    for (String field : requiredFields) {
      required.add(field);
    }
    if (required.size() > 0) {
      inputSchema.add("required", required);
    }

    tool.add("inputSchema", inputSchema);
    return tool;
  }

  private JsonObject permissionPromptToolDefinition() {
    JsonObject tool = new JsonObject();
    tool.addProperty("name", ClaudeCodeManager.PERMISSION_PROMPT_TOOL_NAME);
    tool.addProperty(
        "description",
        "Handle Claude Code permission requests by routing them through the Designer chat UI.");

    JsonObject inputSchema = new JsonObject();
    inputSchema.addProperty("type", "object");

    JsonObject properties = new JsonObject();
    properties.add("tool_name", requiredString("tool_name", "Claude tool requesting permission"));

    JsonObject inputProperty = new JsonObject();
    inputProperty.addProperty("type", "object");
    inputProperty.addProperty(
        "description", "Original tool input payload that Claude wants to execute.");
    properties.add("input", inputProperty);

    inputSchema.add("properties", properties);
    JsonArray required = new JsonArray();
    required.add("tool_name");
    required.add("input");
    inputSchema.add("required", required);
    tool.add("inputSchema", inputSchema);
    return tool;
  }

  private JsonObject optionalString(String name, String description) {
    JsonObject props = new JsonObject();
    JsonObject prop = new JsonObject();
    prop.addProperty("type", "string");
    prop.addProperty("description", description);
    props.add(name, prop);
    return props;
  }

  private JsonObject requiredString(String name, String description) {
    JsonObject props = new JsonObject();
    JsonObject prop = new JsonObject();
    prop.addProperty("type", "string");
    prop.addProperty("description", description);
    props.add(name, prop);
    return props;
  }

  private JsonObject requiredStrings(String... namesAndDescs) {
    JsonObject props = new JsonObject();
    for (int i = 0; i < namesAndDescs.length; i += 2) {
      JsonObject prop = new JsonObject();
      prop.addProperty("type", "string");
      prop.addProperty("description", namesAndDescs[i + 1]);
      props.add(namesAndDescs[i], prop);
    }
    return props;
  }

  private JsonObject optionalInteger(String name, String description) {
    JsonObject props = new JsonObject();
    JsonObject prop = new JsonObject();
    prop.addProperty("type", "integer");
    prop.addProperty("description", description);
    props.add(name, prop);
    return props;
  }

  private JsonObject optionalBoolean(String name, String description) {
    JsonObject props = new JsonObject();
    JsonObject prop = new JsonObject();
    prop.addProperty("type", "boolean");
    prop.addProperty("description", description);
    props.add(name, prop);
    return props;
  }

  private JsonObject requiredStringArray(String name, String description) {
    JsonObject props = new JsonObject();
    JsonObject prop = new JsonObject();
    prop.addProperty("type", "array");
    JsonObject items = new JsonObject();
    items.addProperty("type", "string");
    prop.add("items", items);
    prop.addProperty("description", description);
    props.add(name, prop);
    return props;
  }

  private JsonObject mergeProperties(JsonObject... propertySets) {
    JsonObject merged = new JsonObject();
    for (JsonObject ps : propertySets) {
      for (String key : ps.keySet()) {
        merged.add(key, ps.get(key));
      }
    }
    return merged;
  }

  private JsonObject jsonRpcResult(JsonElement id, JsonObject result) {
    JsonObject resp = new JsonObject();
    resp.addProperty("jsonrpc", "2.0");
    if (id != null) resp.add("id", id);
    resp.add("result", result);
    return resp;
  }

  private JsonObject jsonRpcError(JsonElement id, int code, String message) {
    JsonObject resp = new JsonObject();
    resp.addProperty("jsonrpc", "2.0");
    if (id != null) resp.add("id", id);
    JsonObject error = new JsonObject();
    error.addProperty("code", code);
    error.addProperty("message", message);
    resp.add("error", error);
    return resp;
  }

  private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length > 0 ? bytes.length : -1);
    if (bytes.length > 0) {
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }
    exchange.close();
  }
}
