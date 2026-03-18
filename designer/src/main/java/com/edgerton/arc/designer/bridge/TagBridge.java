package com.edgerton.arc.designer.bridge;

import com.google.gson.*;
import com.inductiveautomation.ignition.client.tags.model.ClientTagManager;
import com.inductiveautomation.ignition.common.browsing.Results;
import com.inductiveautomation.ignition.common.config.Property;
import com.inductiveautomation.ignition.common.config.PropertyValue;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.tags.browsing.NodeDescription;
import com.inductiveautomation.ignition.common.tags.config.TagConfigurationModel;
import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge for read-only tag access in the Designer context.
 *
 * <p>Provides browse, value read, and configuration read operations via the Designer's
 * ClientTagManager. All results are returned as structured JSON strings for MCP tool responses.
 */
public class TagBridge {

  private static final Logger log = LoggerFactory.getLogger("AI-Designer.TagBridge");
  private static final int TIMEOUT_SECONDS = 10;
  private static final int DEFAULT_BROWSE_LIMIT = 500;
  private static final int MAX_BROWSE_LIMIT = 2000;

  private final ClientTagManager tagManager;

  public TagBridge(DesignerContext context) {
    this.tagManager = context.getTagManager();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  browse_tags
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Browse tags at the given path. Returns child tags/folders with metadata.
   *
   * @param path Tag path to browse (e.g. "[default]", "[default]Folder1"). Required.
   * @param depth How many levels deep to recurse. 0 = direct children only (default). 1 = children
   *     + grandchildren. -1 = full recursive.
   * @param limit Maximum total results to return. Capped at MAX_BROWSE_LIMIT.
   * @return JSON string with browse results.
   */
  public String browseTags(String path, int depth, int limit) {
    if (path == null || path.isEmpty()) {
      return errorJson("path is required — use a provider prefix like [default] or [System]");
    }

    if (limit <= 0) limit = DEFAULT_BROWSE_LIMIT;
    if (limit > MAX_BROWSE_LIMIT) limit = MAX_BROWSE_LIMIT;

    try {
      TagPath tagPath = TagPathParser.parse(path);
      JsonObject result = new JsonObject();
      result.addProperty("path", tagPath.toString());

      int[] counter = {0}; // mutable counter for recursive limit tracking
      JsonArray children = browseRecursive(tagPath, depth, limit, counter);

      result.add("results", children);
      result.addProperty("totalResults", counter[0]);
      if (counter[0] >= limit) {
        result.addProperty("truncated", true);
        result.addProperty("limit", limit);
      }

      return result.toString();
    } catch (Exception e) {
      log.error("browseTags failed for path '{}'", path, e);
      return errorJson("Browse failed: " + rootCause(e));
    }
  }

  private JsonArray browseRecursive(TagPath tagPath, int depth, int limit, int[] counter)
      throws Exception {
    JsonArray results = new JsonArray();

    CompletableFuture<Results<NodeDescription>> future = tagManager.browseAsync(tagPath, null);
    Results<NodeDescription> browseResults = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    QualityCode quality = browseResults.getResultQuality();
    if (quality != null && !quality.isGood()) {
      // Return empty array for non-good quality (e.g. path not found)
      return results;
    }

    Collection<NodeDescription> nodes = browseResults.getResults();
    if (nodes == null) return results;

    for (NodeDescription node : nodes) {
      if (counter[0] >= limit) break;
      counter[0]++;

      JsonObject entry = new JsonObject();
      entry.addProperty("name", node.getName());
      entry.addProperty(
          "fullPath", node.getFullPath() != null ? node.getFullPath().toString() : "");

      TagObjectType objectType = node.getObjectType();
      entry.addProperty("tagType", objectType != null ? objectType.name() : "Unknown");

      if (node.getDataType() != null) {
        entry.addProperty("dataType", node.getDataType().toString());
      }
      entry.addProperty("hasChildren", node.hasChildren());

      String subTypeId = node.getSubTypeId();
      if (subTypeId != null && !subTypeId.isEmpty()) {
        entry.addProperty("typeId", subTypeId);
      }

      // Recurse into children if depth allows and node has children
      if (node.hasChildren() && depth != 0 && counter[0] < limit) {
        TagPath childPath = node.getFullPath();
        if (childPath != null) {
          int nextDepth = depth > 0 ? depth - 1 : depth; // -1 stays -1 (infinite)
          JsonArray children = browseRecursive(childPath, nextDepth, limit, counter);
          if (children.size() > 0) {
            entry.add("children", children);
          }
        }
      }

      results.add(entry);
    }

    return results;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  read_tag_values
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Read current values for one or more tag paths.
   *
   * @param paths List of tag path strings (e.g. ["[default]Tag1", "[default]Folder/Tag2"])
   * @return JSON string with value, quality, and timestamp for each path.
   */
  public String readTagValues(List<String> paths) {
    if (paths == null || paths.isEmpty()) {
      return errorJson("paths is required — provide one or more tag paths");
    }

    try {
      List<TagPath> tagPaths = new ArrayList<>(paths.size());
      for (String p : paths) {
        tagPaths.add(TagPathParser.parse(p));
      }

      CompletableFuture<List<QualifiedValue>> future = tagManager.readAsync(tagPaths);
      List<QualifiedValue> values = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      JsonArray results = new JsonArray();
      SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

      for (int i = 0; i < tagPaths.size(); i++) {
        JsonObject entry = new JsonObject();
        entry.addProperty("path", tagPaths.get(i).toString());

        if (i < values.size()) {
          QualifiedValue qv = values.get(i);
          entry.add("value", valueToJson(qv.getValue()));
          entry.addProperty(
              "quality", qv.getQuality() != null ? qv.getQuality().toString() : "Unknown");
          entry.addProperty(
              "timestamp", qv.getTimestamp() != null ? sdf.format(qv.getTimestamp()) : null);
        } else {
          entry.add("value", JsonNull.INSTANCE);
          entry.addProperty("quality", "Error_NotFound");
        }

        results.add(entry);
      }

      JsonObject response = new JsonObject();
      response.add("results", results);
      return response.toString();
    } catch (Exception e) {
      log.error("readTagValues failed", e);
      return errorJson("Read failed: " + rootCause(e));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  read_tag_configuration
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Read full tag configuration for one or more tag paths. For UDT definitions, use _types_ paths
   * (e.g. "[default]_types_/Motor").
   *
   * @param paths List of tag path strings.
   * @param recursive Whether to include child tag configurations.
   * @return JSON string with full configuration for each path.
   */
  public String readTagConfiguration(List<String> paths, boolean recursive) {
    if (paths == null || paths.isEmpty()) {
      return errorJson("paths is required — provide one or more tag paths");
    }

    try {
      List<TagPath> tagPaths = new ArrayList<>(paths.size());
      for (String p : paths) {
        tagPaths.add(TagPathParser.parse(p));
      }

      CompletableFuture<List<TagConfigurationModel>> future =
          tagManager.getTagConfigsAsync(tagPaths, recursive, false);
      List<TagConfigurationModel> configs = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      JsonArray results = new JsonArray();
      for (int i = 0; i < tagPaths.size(); i++) {
        if (i < configs.size()) {
          TagConfigurationModel config = configs.get(i);
          results.add(serializeConfig(config, recursive));
        } else {
          JsonObject entry = new JsonObject();
          entry.addProperty("path", tagPaths.get(i).toString());
          entry.addProperty("error", "Configuration not found");
          results.add(entry);
        }
      }

      JsonObject response = new JsonObject();
      response.add("results", results);
      return response.toString();
    } catch (Exception e) {
      log.error("readTagConfiguration failed", e);
      return errorJson("Configuration read failed: " + rootCause(e));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Config serialization
  // ═══════════════════════════════════════════════════════════════════════

  private JsonObject serializeConfig(TagConfigurationModel config, boolean includeChildren) {
    JsonObject obj = new JsonObject();

    obj.addProperty("name", config.getName());
    if (config.getPath() != null) {
      obj.addProperty("path", config.getPath().toString());
    }
    if (config.getType() != null) {
      obj.addProperty("tagType", config.getType().name());
    }

    // Serialize all tag properties as flat key-value pairs
    for (PropertyValue pv : config) {
      Property<?> prop = pv.getProperty();
      Object value = pv.getValue();
      String propName = prop.getName();

      // Skip internal/noise properties
      if (propName == null || propName.isEmpty()) continue;

      try {
        obj.add(propName, valueToJson(value));
      } catch (Exception e) {
        obj.addProperty(propName, String.valueOf(value));
      }
    }

    // Include children if requested
    if (includeChildren) {
      List<TagConfigurationModel> children = config.getChildren();
      if (children != null && !children.isEmpty()) {
        JsonArray childArray = new JsonArray();
        for (TagConfigurationModel child : children) {
          childArray.add(serializeConfig(child, true));
        }
        obj.add("tags", childArray);
      }
    }

    return obj;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  private JsonElement valueToJson(Object value) {
    if (value == null) return JsonNull.INSTANCE;
    if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
    if (value instanceof Number) return new JsonPrimitive((Number) value);
    if (value instanceof String) return new JsonPrimitive((String) value);
    if (value instanceof Date) {
      SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
      return new JsonPrimitive(sdf.format((Date) value));
    }
    if (value instanceof Enum) return new JsonPrimitive(((Enum<?>) value).name());
    if (value instanceof Collection) {
      JsonArray arr = new JsonArray();
      for (Object item : (Collection<?>) value) {
        arr.add(valueToJson(item));
      }
      return arr;
    }
    if (value instanceof Map) {
      JsonObject map = new JsonObject();
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        map.add(String.valueOf(entry.getKey()), valueToJson(entry.getValue()));
      }
      return map;
    }
    // Fallback: use toString
    return new JsonPrimitive(value.toString());
  }

  private String errorJson(String message) {
    JsonObject error = new JsonObject();
    error.addProperty("error", message);
    return error.toString();
  }

  private String rootCause(Exception e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    return t.getClass().getSimpleName() + ": " + t.getMessage();
  }
}
