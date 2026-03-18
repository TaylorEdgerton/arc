package com.edgerton.arc.designer.bridge;

import com.google.gson.*;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge for read-only named query access in the Designer context.
 *
 * <p>Provides listing and reading of named query resources via the Designer project's resource
 * collection API. All results are returned as structured JSON strings for MCP tool responses.
 */
public class NamedQueryBridge {

  private static final Logger log = LoggerFactory.getLogger("Arc-Module.NamedQueryBridge");
  private static final ResourceType NAMED_QUERY = new ResourceType("ignition", "named-query");

  private final DesignerContext context;

  public NamedQueryBridge(DesignerContext context) {
    this.context = context;
  }

  /**
   * List all named query resources in the project, grouped by folder path.
   *
   * @return JSON string with query paths grouped by folder.
   */
  public String listNamedQueries() {
    try {
      var project = context.getProject();
      // Map folder path -> list of query names
      Map<String, List<String>> grouped = new TreeMap<>();

      for (var resource : project.getResourcesOfType(NAMED_QUERY)) {
        if (resource.isFolder()) continue;

        String fullPath = resource.getResourcePath().toString();
        // Extract folder and name from the full resource path
        // Format: "ignition/named-query/folder/subfolder/queryName"
        String relativePath = fullPath;
        String prefix = "ignition/named-query/";
        if (relativePath.startsWith(prefix)) {
          relativePath = relativePath.substring(prefix.length());
        }

        int lastSlash = relativePath.lastIndexOf('/');
        String folder = lastSlash > 0 ? relativePath.substring(0, lastSlash) : "/";
        String name = lastSlash > 0 ? relativePath.substring(lastSlash + 1) : relativePath;

        grouped.computeIfAbsent(folder, k -> new ArrayList<>()).add(name);
      }

      JsonObject result = new JsonObject();
      if (grouped.isEmpty()) {
        result.add("queries", new JsonObject());
        result.addProperty("totalCount", 0);
        return result.toString();
      }

      JsonObject queriesObj = new JsonObject();
      int totalCount = 0;
      for (var entry : grouped.entrySet()) {
        JsonArray names = new JsonArray();
        for (String name : entry.getValue()) {
          names.add(name);
          totalCount++;
        }
        queriesObj.add(entry.getKey(), names);
      }

      result.add("queries", queriesObj);
      result.addProperty("totalCount", totalCount);

      log.debug("Listed {} named queries across {} folders", totalCount, grouped.size());
      return result.toString();
    } catch (Exception e) {
      log.error("Error listing named queries", e);
      return errorJson("Failed to list named queries: " + e.getMessage());
    }
  }

  /**
   * Read a named query's full definition including SQL text and all metadata.
   *
   * @param queryPath Query path — full path (e.g. "ignition/named-query/folder/myQuery") or bare
   *     path (e.g. "folder/myQuery" or "myQuery").
   * @return JSON string with SQL text and all available metadata.
   */
  public String readNamedQuery(String queryPath) {
    if (queryPath == null || queryPath.isEmpty()) {
      return errorJson("query_path is required");
    }

    try {
      String barePath = queryPath;
      String prefix = "ignition/named-query/";
      if (queryPath.startsWith(prefix)) {
        barePath = queryPath.substring(prefix.length());
      }

      var resourcePath = NAMED_QUERY.subPath(barePath);
      var resourceOpt = context.getProject().getResource(resourcePath);

      if (resourceOpt.isEmpty()) {
        return errorJson("Named query not found: " + queryPath);
      }

      var resource = resourceOpt.get();
      JsonObject result = new JsonObject();
      result.addProperty("path", barePath);

      // Read query.sql — the SQL text
      var sqlOpt = resource.getData("query.sql");
      if (sqlOpt.isPresent()) {
        String sql = new String(sqlOpt.get().getBytes(), StandardCharsets.UTF_8);
        result.addProperty("sql", sql);
      }

      // Read all other data keys for metadata (params, connection, query type, etc.)
      Set<String> dataKeys = resource.getDataKeys();
      for (String key : dataKeys) {
        if ("query.sql".equals(key)) continue; // already handled

        var dataOpt = resource.getData(key);
        if (dataOpt.isPresent()) {
          String content = new String(dataOpt.get().getBytes(), StandardCharsets.UTF_8);

          // Try to parse as JSON for structured metadata
          try {
            JsonElement parsed = JsonParser.parseString(content);
            // Use the key name without extension as the property name
            String propName = key.contains(".") ? key.substring(0, key.lastIndexOf('.')) : key;
            result.add(propName, parsed);
          } catch (JsonSyntaxException e) {
            // Not JSON — store as raw string
            result.addProperty(key, content);
          }
        }
      }

      // Include available data keys for transparency
      JsonArray keysArray = new JsonArray();
      for (String key : dataKeys) {
        keysArray.add(key);
      }
      result.add("dataKeys", keysArray);

      return result.toString();
    } catch (Exception e) {
      log.error("Error reading named query '{}'", queryPath, e);
      return errorJson("Failed to read named query: " + e.getMessage());
    }
  }

  private String errorJson(String message) {
    JsonObject error = new JsonObject();
    error.addProperty("error", message);
    return error.toString();
  }
}
