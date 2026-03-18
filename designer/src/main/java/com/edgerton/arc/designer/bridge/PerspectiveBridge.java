package com.edgerton.arc.designer.bridge;

import com.google.gson.*;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge to the Perspective Designer's ViewEditorJsBridge for live view manipulation.
 *
 * <p>Discovery chain: DesignerContext -> Perspective module hook -> workspace -> active editor ->
 * JavaJsBridge
 *
 * <p>The bridge is NOT cached because the active editor tab changes when the user switches views.
 * The workspace IS cached once found (it stays stable).
 *
 * <p>Path convention: bridge methods expect bare numeric index paths like "0:1:2". The JS side
 * internally prepends "D.". Human-readable paths (root/Table_0) are resolved to numeric paths via
 * the view JSON tree before any bridge call.
 */
public class PerspectiveBridge {

  private static final Logger log = LoggerFactory.getLogger("Arc-Module.PerspectiveBridge");

  private static final ResourceType PERSPECTIVE_VIEWS =
      new ResourceType("com.inductiveautomation.perspective", "views");
  private static final String PERSPECTIVE_MODULE_ID = "com.inductiveautomation.perspective";
  private static final String BRIDGE_CLASS_HINT = "JavaJsBridge";
  private static final String WORKSPACE_CLASS_HINT = "TabbedResourceWorkspace";

  private final DesignerContext context;
  private Object cachedWorkspace;
  private String lastLoggedBridgeClass;
  private Class<?> cachedPropertyTypeClass;

  public PerspectiveBridge(DesignerContext context) {
    this.context = context;
  }

  // =====================================================================
  //  View listing (resource-based — always works)
  // =====================================================================

  public List<String> getAvailableViews() {
    List<String> viewPaths = new ArrayList<>();
    try {
      var project = context.getProject();
      for (var resource : project.getResourcesOfType(PERSPECTIVE_VIEWS)) {
        viewPaths.add(resource.getResourcePath().toString());
      }
      log.debug("Listed {} Perspective views", viewPaths.size());
    } catch (Exception e) {
      log.error("Error listing views", e);
      viewPaths.add("[Error listing views: " + e.getMessage() + "]");
    }
    return viewPaths;
  }

  public String readViewResource(String viewPath) {
    try {
      var project = context.getProject();
      // Strip the module prefix if present to get the bare path for subPath lookup
      // ResourcePath.toString() returns "com.inductiveautomation.perspective/views/path"
      String barePath = viewPath;
      String prefix = PERSPECTIVE_MODULE_ID + "/views/";
      if (viewPath.startsWith(prefix)) {
        barePath = viewPath.substring(prefix.length());
      }
      var resourcePath = PERSPECTIVE_VIEWS.subPath(barePath);
      var resourceOpt = project.getResource(resourcePath);
      if (resourceOpt.isPresent()) {
        var dataOpt = resourceOpt.get().getData("view.json");
        if (dataOpt.isPresent()) {
          String json = new String(dataOpt.get().getBytes(), StandardCharsets.UTF_8);
          return annotateWithIndexPaths(json);
        }
        return "{\"error\": \"View resource found but no view.json data: " + viewPath + "\"}";
      }
      return "{\"error\": \"View not found: " + viewPath + "\"}";
    } catch (Exception e) {
      log.error("Error reading view resource '{}'", viewPath, e);
      return "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
    }
  }

  // =====================================================================
  //  Script listing & reading (resource-based)
  // =====================================================================

  private static final ResourceType SCRIPT_PYTHON = new ResourceType("ignition", "script-python");
  private static final ResourceType EVENT_SCRIPTS = new ResourceType("ignition", "event-scripts");

  /**
   * List all script resources grouped by type. Returns JSON: {"script-python": [...],
   * "event-scripts": [...]}
   */
  public String listScripts() {
    JsonObject result = new JsonObject();
    try {
      var project = context.getProject();

      JsonArray scriptPython = new JsonArray();
      for (var resource : project.getResourcesOfType(SCRIPT_PYTHON)) {
        if (!resource.isFolder()) {
          scriptPython.add(resource.getResourcePath().toString());
        }
      }
      result.add("script-python", scriptPython);

      JsonArray eventScripts = new JsonArray();
      for (var resource : project.getResourcesOfType(EVENT_SCRIPTS)) {
        if (!resource.isFolder()) {
          eventScripts.add(resource.getResourcePath().toString());
        }
      }
      result.add("event-scripts", eventScripts);

      log.debug(
          "Listed scripts: {} script-python, {} event-scripts",
          scriptPython.size(),
          eventScripts.size());
    } catch (Exception e) {
      log.error("Error listing scripts", e);
      result.addProperty("error", e.getMessage());
    }
    return result.toString();
  }

  /**
   * Read a script resource's Python source code. Accepts full path (e.g.
   * "ignition/script-python/myPkg/myMod") or bare path ("myPkg/myMod").
   */
  public String readScript(String scriptPath) {
    try {
      // Try script-python first, then event-scripts
      String content = readScriptFromType(SCRIPT_PYTHON, "ignition/script-python/", scriptPath);
      if (content != null) return content;

      content = readScriptFromType(EVENT_SCRIPTS, "ignition/event-scripts/", scriptPath);
      if (content != null) return content;

      return "{\"error\": \"Script not found: " + scriptPath.replace("\"", "\\\"") + "\"}";
    } catch (Exception e) {
      log.error("Error reading script '{}'", scriptPath, e);
      return "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
    }
  }

  private String readScriptFromType(ResourceType type, String prefix, String scriptPath) {
    try {
      String barePath = scriptPath;
      if (scriptPath.startsWith(prefix)) {
        barePath = scriptPath.substring(prefix.length());
      }
      var resourcePath = type.subPath(barePath);
      var resourceOpt = context.getProject().getResource(resourcePath);
      if (resourceOpt.isPresent()) {
        var resource = resourceOpt.get();

        // Try code.py first (confirmed for script-python), then fall back to any .py key
        var dataOpt = resource.getData("code.py");
        if (dataOpt.isPresent()) {
          return new String(dataOpt.get().getBytes(), StandardCharsets.UTF_8);
        }

        // Fallback: try all data keys for a readable .py file
        for (String key : resource.getDataKeys()) {
          if (key.endsWith(".py")) {
            var altData = resource.getData(key);
            if (altData.isPresent()) {
              return new String(altData.get().getBytes(), StandardCharsets.UTF_8);
            }
          }
        }

        // Report what keys are available
        return "{\"error\": \"Script resource found but no Python data. Available keys: "
            + resource.getDataKeys()
            + "\"}";
      }
    } catch (Exception e) {
      log.debug("readScriptResource({}, {}) failed: {}", type, scriptPath, e.getMessage());
    }
    return null;
  }

  // =====================================================================
  //  Workspace discovery
  // =====================================================================

  private Object findPerspectiveWorkspace() {
    if (cachedWorkspace != null) return cachedWorkspace;

    log.info("Searching for Perspective workspace...");

    // Strategy 1: Module hook -> methods/fields returning a workspace
    try {
      Object hook = context.getModule(PERSPECTIVE_MODULE_ID);
      if (hook != null) {
        log.info("Perspective hook: {}", hook.getClass().getName());

        // 1a: getter methods returning a Workspace type
        for (Method m : hook.getClass().getMethods()) {
          if (m.getParameterCount() == 0
              && m.getReturnType().getName().toLowerCase().contains("workspace")) {
            try {
              Object result = m.invoke(hook);
              if (result != null) {
                log.info(
                    "Found workspace via hook.{}(): {}", m.getName(), result.getClass().getName());
                cachedWorkspace = result;
                return result;
              }
            } catch (Exception e) {
              /* skip */
            }
          }
        }

        // 1b: deep field search from hook
        Object ws = deepFieldSearch(hook, WORKSPACE_CLASS_HINT, 4, new HashSet<>());
        if (ws != null) {
          log.info("Found workspace via deep field search: {}", ws.getClass().getName());
          cachedWorkspace = ws;
          return ws;
        }

        // 1c: fields named "workspace"
        for (Class<?> c = hook.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
          for (Field f : c.getDeclaredFields()) {
            if (f.getName().toLowerCase().contains("workspace")) {
              try {
                f.setAccessible(true);
                Object val = f.get(hook);
                if (val != null) {
                  log.info(
                      "Found workspace via field '{}': {}", f.getName(), val.getClass().getName());
                  cachedWorkspace = val;
                  return val;
                }
              } catch (Exception e) {
                /* skip */
              }
            }
          }
        }
      }
    } catch (Exception e) {
      log.error("Error searching hook for workspace", e);
    }

    // Strategy 2: DockingManager frames
    try {
      var dm = context.getDockingManager();
      if (dm != null && dm.getAllFrameNames() != null) {
        for (var name : dm.getAllFrameNames()) {
          if (name.toString().toLowerCase().contains("perspective")) {
            var frame = dm.getFrame(name.toString());
            if (frame != null) {
              Object ws = deepFieldSearch(frame, WORKSPACE_CLASS_HINT, 3, new HashSet<>());
              if (ws != null) {
                log.info("Found workspace in DockingManager frame: {}", ws.getClass().getName());
                cachedWorkspace = ws;
                return ws;
              }
            }
          }
        }
      }
    } catch (Exception e) {
      log.error("Error searching DockingManager", e);
    }

    // Strategy 3: Swing window walk
    try {
      for (Window window : Window.getWindows()) {
        Object ws = findInComponentTree(window, WORKSPACE_CLASS_HINT);
        if (ws != null) {
          log.info("Found workspace in Swing tree: {}", ws.getClass().getName());
          cachedWorkspace = ws;
          return ws;
        }
      }
    } catch (Exception e) {
      log.error("Error searching Swing windows", e);
    }

    log.warn("WORKSPACE NOT FOUND after all strategies");
    return null;
  }

  // =====================================================================
  //  Bridge discovery (per-call — active tab changes)
  // =====================================================================

  private Object findActiveBridge() {
    Object workspace = findPerspectiveWorkspace();
    if (workspace == null) return null;

    // Strategy 1: active editor -> deep field search
    try {
      Object editor = findActiveEditor(workspace);
      if (editor != null) {
        Object bridge = deepFieldSearch(editor, BRIDGE_CLASS_HINT, 5, new HashSet<>());
        if (bridge != null) {
          logBridgeMethods(bridge);
          return bridge;
        }
        if (editor instanceof Container) {
          bridge = findBridgeInSwingTree((Container) editor, new HashSet<>());
          if (bridge != null) {
            logBridgeMethods(bridge);
            return bridge;
          }
        }
      }
    } catch (Exception e) {
      log.error("Error finding bridge via active editor", e);
    }

    // Strategy 2: deep search from workspace
    try {
      Object bridge = deepFieldSearch(workspace, BRIDGE_CLASS_HINT, 6, new HashSet<>());
      if (bridge != null) {
        logBridgeMethods(bridge);
        return bridge;
      }
    } catch (Exception e) {
      log.error("Error in workspace deep search", e);
    }

    // Strategy 3: workspace Swing tree
    if (workspace instanceof Container) {
      try {
        Object bridge = findBridgeInSwingTree((Container) workspace, new HashSet<>());
        if (bridge != null) {
          logBridgeMethods(bridge);
          return bridge;
        }
      } catch (Exception e) {
        log.error("Error searching workspace Swing tree", e);
      }
    }

    log.warn("BRIDGE NOT FOUND");
    return null;
  }

  private Object findActiveEditor(Object workspace) {
    String[] methodNames = {
      "getSelectedEditor",
      "getActiveEditor",
      "getCurrentEditor",
      "getSelectedComponent",
      "getSelectedTab"
    };
    for (String name : methodNames) {
      try {
        Method m = findMethod(workspace.getClass(), name, 0);
        if (m != null) {
          m.setAccessible(true);
          Object result = m.invoke(workspace);
          if (result != null) return result;
        }
      } catch (Exception e) {
        /* skip */
      }
    }

    // Broad search: methods returning editor-like types
    for (Method m : workspace.getClass().getMethods()) {
      if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) continue;
      String mn = m.getName().toLowerCase();
      if ((mn.contains("selected") || mn.contains("active") || mn.contains("current"))
          && m.getReturnType() != void.class
          && m.getReturnType() != boolean.class) {
        try {
          m.setAccessible(true);
          Object result = m.invoke(workspace);
          if (result != null) {
            // Only accept if it actually contains a bridge
            if (deepFieldSearch(result, BRIDGE_CLASS_HINT, 4, new HashSet<>()) != null) {
              return result;
            }
          }
        } catch (Exception e) {
          /* skip */
        }
      }
    }

    // Last resort: JTabbedPane
    if (workspace instanceof Container) {
      JTabbedPane tabPane = findTabbedPane((Container) workspace);
      if (tabPane != null && tabPane.getSelectedComponent() != null) {
        return tabPane.getSelectedComponent();
      }
    }
    return null;
  }

  // =====================================================================
  //  Reflection utilities (minimal)
  // =====================================================================

  private Object deepFieldSearch(
      Object root, String classHint, int maxDepth, Set<Integer> visited) {
    if (root == null || maxDepth <= 0) return null;
    int id = System.identityHashCode(root);
    if (visited.contains(id)) return null;
    visited.add(id);

    for (Class<?> c = root.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field f : c.getDeclaredFields()) {
        try {
          f.setAccessible(true);
          Object value = f.get(root);
          if (value == null) continue;
          if (classHierarchyContains(value.getClass(), classHint)) return value;
          if (!isSimpleType(value.getClass())) {
            Object found = deepFieldSearch(value, classHint, maxDepth - 1, visited);
            if (found != null) return found;
          }
        } catch (Exception e) {
          /* skip */
        }
      }
    }
    return null;
  }

  private boolean classHierarchyContains(Class<?> clazz, String hint) {
    String lower = hint.toLowerCase();
    for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
      if (c.getName().toLowerCase().contains(lower)
          || c.getSimpleName().toLowerCase().contains(lower)) return true;
      for (Class<?> iface : c.getInterfaces()) {
        if (iface.getName().toLowerCase().contains(lower)) return true;
      }
    }
    return false;
  }

  private Object findInComponentTree(Container container, String classHint) {
    if (classHierarchyContains(container.getClass(), classHint)) return container;
    for (Component child : container.getComponents()) {
      if (child instanceof Container) {
        Object found = findInComponentTree((Container) child, classHint);
        if (found != null) return found;
      }
    }
    return null;
  }

  private Object findBridgeInSwingTree(Container container, Set<Integer> visited) {
    for (Component child : container.getComponents()) {
      Object bridge = deepFieldSearch(child, BRIDGE_CLASS_HINT, 3, visited);
      if (bridge != null) return bridge;
      if (child instanceof Container) {
        bridge = findBridgeInSwingTree((Container) child, visited);
        if (bridge != null) return bridge;
      }
    }
    return null;
  }

  private JTabbedPane findTabbedPane(Container container) {
    for (Component child : container.getComponents()) {
      if (child instanceof JTabbedPane) return (JTabbedPane) child;
      if (child instanceof Container) {
        JTabbedPane found = findTabbedPane((Container) child);
        if (found != null) return found;
      }
    }
    return null;
  }

  private boolean isSimpleType(Class<?> c) {
    return c.isPrimitive()
        || c == String.class
        || Number.class.isAssignableFrom(c)
        || c == Boolean.class
        || c == Character.class
        || c.isEnum()
        || c.isArray()
        || c.getName().startsWith("java.lang.")
        || c.getName().startsWith("java.util.")
        || c.getName().startsWith("java.io.")
        || c.getName().startsWith("java.time.");
  }

  private Method findMethod(Class<?> clazz, String name, int paramCount) {
    for (Method m : clazz.getMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
    }
    return null;
  }

  private List<Method> findAllMethods(Class<?> clazz, String name) {
    List<Method> result = new ArrayList<>();
    for (Method m : clazz.getMethods()) {
      if (m.getName().equals(name)) result.add(m);
    }
    return result;
  }

  private Field findField(Class<?> clazz, String name) {
    for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field f : c.getDeclaredFields()) {
        if (f.getName().equals(name)) return f;
      }
    }
    return null;
  }

  private List<Field> getAllFields(Class<?> clazz) {
    List<Field> fields = new ArrayList<>();
    for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
      fields.addAll(Arrays.asList(c.getDeclaredFields()));
    }
    return fields;
  }

  // =====================================================================
  //  EDT helper + bridge invocation
  // =====================================================================

  private String runOnEdt(Callable<String> task) {
    try {
      if (SwingUtilities.isEventDispatchThread()) return task.call();
      CompletableFuture<String> future = new CompletableFuture<>();
      SwingUtilities.invokeLater(
          () -> {
            try {
              future.complete(task.call());
            } catch (Exception e) {
              future.completeExceptionally(e);
            }
          });
      return future.get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      log.error("EDT task failed", e);
      return null;
    }
  }

  /** Invokes a zero-or-more-String-parameter bridge method, handling CompletableFuture returns. */
  private String invokeBridgeMethod(String methodName, String... args) {
    Object bridge = findActiveBridge();
    if (bridge == null) return null;

    final Object br = bridge;
    return runOnEdt(
        () -> {
          for (Method m : findAllMethods(br.getClass(), methodName)) {
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != args.length) continue;
            // Check all params are String-compatible
            boolean ok = true;
            for (Class<?> p : pt) {
              if (p != String.class && p != Object.class && p != CharSequence.class) {
                ok = false;
                break;
              }
            }
            if (!ok) continue;

            try {
              m.setAccessible(true);
              Object result = m.invoke(br, (Object[]) args);
              if (result instanceof CompletableFuture<?>) {
                result = ((CompletableFuture<?>) result).get(10, TimeUnit.SECONDS);
              }
              return result != null ? result.toString() : "ok";
            } catch (Exception e) {
              Throwable cause = e.getCause() != null ? e.getCause() : e;
              log.debug("{}() failed: {}", methodName, cause.getMessage());
            }
          }
          return null;
        });
  }

  /**
   * sendRaw(String protocol, String payloadJson) fallback — works for most operations. MUST be
   * called on EDT.
   */
  private String sendRaw(Object bridge, String protocol, String payloadJson) {
    Method m = findMethod(bridge.getClass(), "sendRaw", 2);
    if (m == null) return null;
    try {
      m.setAccessible(true);
      Object result = m.invoke(bridge, protocol, payloadJson);
      if (result instanceof CompletableFuture<?>) {
        result = ((CompletableFuture<?>) result).get(10, TimeUnit.SECONDS);
      }
      String s = result != null ? result.toString() : "ok";
      log.info("sendRaw('{}') returned: '{}'", protocol, s);
      return s;
    } catch (Exception e) {
      log.debug("sendRaw('{}') failed: {}", protocol, e.getMessage());
      return null;
    }
  }

  /** Resolves the PROTOCOL string from a change class's static PROTOCOL field. */
  private String resolveProtocol(Class<?> changeClass, String fallback) {
    try {
      Field f = changeClass.getDeclaredField("PROTOCOL");
      f.setAccessible(true);
      Object val = f.get(null);
      if (val instanceof String && !((String) val).isEmpty()) return (String) val;
    } catch (Exception e) {
      /* use fallback */
    }
    return fallback;
  }

  // =====================================================================
  //  Path resolution — human-readable <-> numeric index path
  // =====================================================================

  /**
   * Converts human-readable paths to numeric index paths: "root/Table_0" -> "D.0:1"
   * "D.root/Table_0" -> "D.0:1" "Table_0" -> "D.0:1" (bare name search) "D.0:2" -> "D.0:2" (already
   * numeric)
   */
  private String resolveToIndexPath(String humanPath) {
    if (humanPath == null || humanPath.isEmpty()) return null;

    // Already numeric? Pass through.
    if (humanPath.matches("^([CDTBLR]\\.)\\d+(:\\d+)*$") || humanPath.matches("^\\d+(:\\d*)*$")) {
      return humanPath;
    }

    // Strip single-letter-dot prefix
    String cleanPath = humanPath;
    if (cleanPath.length() > 2
        && Character.isLetter(cleanPath.charAt(0))
        && cleanPath.charAt(1) == '.') {
      cleanPath = cleanPath.substring(2);
    }

    String viewJson = getViewJsonForResolution();
    if (viewJson == null) {
      log.warn("Cannot resolve path '{}' — no view JSON available", humanPath);
      return null;
    }

    try {
      JsonObject viewObj = JsonParser.parseString(viewJson).getAsJsonObject();
      JsonObject root =
          viewObj.has("root") && viewObj.get("root").isJsonObject()
              ? viewObj.getAsJsonObject("root")
              : (viewObj.has("children") || viewObj.has("type")) ? viewObj : null;
      if (root == null) return null;

      String[] segments = cleanPath.split("/");
      String rootName = getComponentName(root);
      boolean startsWithRoot = segments[0].equals("root") || segments[0].equals(rootName);

      if (startsWithRoot) {
        if (segments.length == 1) return "D.0";
        String result = walkTree(root, segments, 1);
        return result != null ? "D." + result : null;
      } else {
        String result = searchForComponent(root, cleanPath, "0");
        return result != null ? "D." + result : null;
      }
    } catch (Exception e) {
      log.error("Error resolving path '{}'", humanPath, e);
      return null;
    }
  }

  private String getViewJsonForResolution() {
    try {
      String json = invokeBridgeMethod("getOwnViewConfig");
      if (json != null && json.startsWith("{")) return json;
    } catch (Exception e) {
      /* fallback */
    }

    try {
      String viewPath = getActiveViewPath();
      if (viewPath != null) {
        String json = readViewResource(viewPath);
        if (json != null && json.startsWith("{") && !json.contains("\"error\"")) return json;
      }
    } catch (Exception e) {
      /* no fallback */
    }
    return null;
  }

  private String getActiveViewPath() {
    Object workspace = findPerspectiveWorkspace();
    if (workspace == null) return null;
    return runOnEdt(
        () -> {
          for (String editorMethod :
              new String[] {"getSelectedResourceEditor", "getActiveEditor"}) {
            Method em = findMethod(workspace.getClass(), editorMethod, 0);
            if (em == null) continue;
            em.setAccessible(true);
            Object editor = em.invoke(workspace);
            if (editor == null) continue;
            for (String pathMethod : new String[] {"getResourcePath", "getPath", "getViewPath"}) {
              Method pm = findMethod(editor.getClass(), pathMethod, 0);
              if (pm == null) continue;
              pm.setAccessible(true);
              Object path = pm.invoke(editor);
              if (path != null) {
                String ps = path.toString();
                return ps.contains("views/") ? ps.substring(ps.indexOf("views/") + 6) : ps;
              }
            }
          }
          return null;
        });
  }

  private String walkTree(JsonObject component, String[] segments, int startIndex) {
    StringBuilder indexPath = new StringBuilder("0");
    JsonObject current = component;

    for (int i = startIndex; i < segments.length; i++) {
      String target = segments[i];
      JsonArray children =
          current.has("children") && current.get("children").isJsonArray()
              ? current.getAsJsonArray("children")
              : null;
      if (children == null || children.isEmpty()) return null;

      boolean found = false;
      for (int ci = 0; ci < children.size(); ci++) {
        if (!children.get(ci).isJsonObject()) continue;
        JsonObject child = children.get(ci).getAsJsonObject();
        if (target.equals(getComponentName(child))) {
          indexPath.append(":").append(ci);
          current = child;
          found = true;
          break;
        }
      }
      if (!found) return null;
    }
    return indexPath.toString();
  }

  private String searchForComponent(JsonObject component, String targetPath, String currentIndex) {
    String[] parts = targetPath.split("/", 2);
    String targetName = parts[0];
    JsonArray children =
        component.has("children") && component.get("children").isJsonArray()
            ? component.getAsJsonArray("children")
            : null;
    if (children == null) return null;

    for (int i = 0; i < children.size(); i++) {
      if (!children.get(i).isJsonObject()) continue;
      JsonObject child = children.get(i).getAsJsonObject();
      String childIndex = currentIndex + ":" + i;

      if (targetName.equals(getComponentName(child))) {
        if (parts.length == 1) return childIndex;
        String result = searchForComponent(child, parts[1], childIndex);
        if (result != null) return result;
      }

      // Deep search for bare names
      if (parts.length == 1) {
        String deep = searchForComponent(child, targetPath, childIndex);
        if (deep != null) return deep;
      }
    }
    return null;
  }

  private String getComponentName(JsonObject component) {
    if (component.has("meta") && component.get("meta").isJsonObject()) {
      JsonObject meta = component.getAsJsonObject("meta");
      if (meta.has("name") && meta.get("name").isJsonPrimitive())
        return meta.get("name").getAsString();
    }
    return component.has("type") && component.get("type").isJsonPrimitive()
        ? component.get("type").getAsString()
        : "unknown";
  }

  /** Resolves path or returns null (never passes invalid paths to bridge). */
  private String resolveOrError(String humanPath) {
    String resolved = resolveToIndexPath(humanPath);
    if (resolved != null) return resolved;
    log.warn("Path resolution FAILED for '{}' — will NOT pass to bridge", humanPath);
    return null;
  }

  /** Strips the "D." prefix for bridge calls. The JS side adds "D." internally. */
  private String stripPrefix(String path) {
    if (path != null
        && path.length() > 2
        && Character.isLetter(path.charAt(0))
        && path.charAt(1) == '.') {
      return path.substring(2);
    }
    return path;
  }

  // =====================================================================
  //  PropertyType resolution
  // =====================================================================

  /** Discovers the PropertyType enum class via bridge method parameter inspection. */
  private Class<?> resolvePropertyTypeClass(Object bridge) {
    if (cachedPropertyTypeClass != null) return cachedPropertyTypeClass;

    for (String methodName : new String[] {"writeProperty", "write"}) {
      for (Method m : findAllMethods(bridge.getClass(), methodName)) {
        if (m.getParameterCount() < 2) continue;
        Class<?> changeClass = m.getParameterTypes()[0];
        for (var ctor : changeClass.getDeclaredConstructors()) {
          for (Class<?> p : ctor.getParameterTypes()) {
            if (p.isEnum() && p.getName().toLowerCase().contains("propertytype")) {
              cachedPropertyTypeClass = p;
              log.info("Discovered PropertyType: {}", p.getName());
              return p;
            }
          }
        }
      }
    }

    // Fallback: Class.forName
    try {
      Class<?> pt = Class.forName("com.inductiveautomation.perspective.common.api.PropertyType");
      if (pt.isEnum()) {
        cachedPropertyTypeClass = pt;
        return pt;
      }
    } catch (ClassNotFoundException e) {
      /* skip */
    }

    log.warn("PropertyType enum not found");
    return null;
  }

  /** Splits "props.data" into [PropertyType.PROPS, "data"]. */
  private Object[] splitPropertyPath(Class<?> propertyTypeClass, String fullPath) {
    if (fullPath == null) return null;
    int dot = fullPath.indexOf('.');
    String prefix = dot >= 0 ? fullPath.substring(0, dot) : fullPath;
    String subPath = dot >= 0 ? fullPath.substring(dot + 1) : "";

    for (Object enumConst : propertyTypeClass.getEnumConstants()) {
      String enumName = enumConst.toString().toLowerCase();
      if (enumName.equals(prefix.toLowerCase())
          || enumName.startsWith(prefix.toLowerCase())
          || prefix.toLowerCase().startsWith(enumName)) {
        return new Object[] {enumConst, subPath};
      }
    }
    log.warn("No PropertyType matches prefix '{}' from '{}'", prefix, fullPath);
    return null;
  }

  // =====================================================================
  //  Public API — Reads
  // =====================================================================

  public String getOpenViewName() {
    String viewPath = invokeBridgeMethod("getOwnViewPath");
    return viewPath != null
        ? viewPath
        : "{\"error\": \"Could not determine open view name. Is a Perspective view open?\"}";
  }

  public String getCurrentViewConfig() {
    return getCurrentViewConfig(null);
  }

  public String getCurrentViewConfig(String viewPath) {
    String liveConfig = invokeBridgeMethod("getOwnViewConfig");
    if (liveConfig != null) return annotateWithIndexPaths(liveConfig);
    if (viewPath != null) return annotateWithIndexPaths(readViewResource(viewPath));
    return "{\"error\": \"No live bridge and no viewPath provided.\"}";
  }

  public String getComponentDetails(String componentPath) {
    String bridgePath = stripPrefix(componentPath);
    String result = invokeBridgeMethod("getComponentDetails", bridgePath);
    return result != null
        ? result
        : "{\"error\": \"Bridge unavailable. Component details require a live editor.\"}";
  }

  /** Annotates view JSON with an _indexPaths map and readable summary. */
  private String annotateWithIndexPaths(String viewJson) {
    if (viewJson == null || !viewJson.startsWith("{")) return viewJson;
    try {
      JsonObject viewObj = JsonParser.parseString(viewJson).getAsJsonObject();
      JsonObject root =
          viewObj.has("root") && viewObj.get("root").isJsonObject()
              ? viewObj.getAsJsonObject("root")
              : (viewObj.has("children") || viewObj.has("type")) ? viewObj : null;
      if (root == null) return viewJson;

      JsonObject indexMap = new JsonObject();
      buildIndexMap(root, "D.0", indexMap);
      viewObj.add("_indexPaths", indexMap);

      StringBuilder summary = new StringBuilder();
      summary.append("\n\n--- COMPONENT INDEX PATH MAP ---\n");
      summary.append("Use these index paths when calling tools that modify components:\n");
      buildReadableSummary(root, "D.0", summary, 0);
      summary.append("--- END INDEX PATH MAP ---\n");

      return viewObj.toString() + summary.toString();
    } catch (Exception e) {
      log.warn("Failed to annotate view JSON: {}", e.getMessage());
      return viewJson;
    }
  }

  private void buildIndexMap(JsonObject component, String indexPath, JsonObject map) {
    String name = getComponentName(component);
    String type = component.has("type") ? component.get("type").getAsString() : "unknown";
    map.addProperty(name + " (" + type + ")", indexPath);

    if (component.has("children") && component.get("children").isJsonArray()) {
      JsonArray children = component.getAsJsonArray("children");
      for (int i = 0; i < children.size(); i++) {
        if (children.get(i).isJsonObject())
          buildIndexMap(children.get(i).getAsJsonObject(), indexPath + ":" + i, map);
      }
    }
  }

  private void buildReadableSummary(
      JsonObject component, String indexPath, StringBuilder sb, int depth) {
    String indent = "  ".repeat(depth);
    String name = getComponentName(component);
    String type = component.has("type") ? component.get("type").getAsString() : "unknown";
    sb.append(indent)
        .append(indexPath)
        .append("  ->  ")
        .append(name)
        .append(" (")
        .append(type)
        .append(")")
        .append("\n");

    if (component.has("children") && component.get("children").isJsonArray()) {
      JsonArray children = component.getAsJsonArray("children");
      for (int i = 0; i < children.size(); i++) {
        if (children.get(i).isJsonObject())
          buildReadableSummary(
              children.get(i).getAsJsonObject(), indexPath + ":" + i, sb, depth + 1);
      }
    }
  }

  // =====================================================================
  //  DISABLED — Write/mutate methods stubbed for read-only release.
  //  Re-enable when US-007 / US-009 are ready for development.
  //  Original implementations recoverable from git history (commit prior to US-017).
  // =====================================================================

  public String writeProperty(String componentPath, String propertyPath, String jsonValue) {
    return "{\"error\": \"write_property is disabled in this release (read-only mode)\"}";
  }

  public String alterPropertyConfig(String componentPath, String configJson) {
    return "{\"error\": \"add_binding is disabled in this release (read-only mode)\"}";
  }

  public String alterEventConfig(String componentPath, String eventConfigJson) {
    return "{\"error\": \"alter_event_config is disabled in this release (read-only mode)\"}";
  }

  public String addComponents(String parentPath, String componentsJson) {
    return "{\"error\": \"add_component is disabled in this release (read-only mode)\"}";
  }

  public String deleteComponents(String componentPath) {
    return "{\"error\": \"delete_component is disabled in this release (read-only mode)\"}";
  }

  // =====================================================================
  //  Public API — setSelection
  // =====================================================================

  public String setSelection(String componentPath) {
    String effectivePath = resolveOrError(componentPath);
    if (effectivePath == null) return pathError(componentPath);

    Object bridge = findActiveBridge();
    if (bridge == null) return "{\"error\": \"Bridge unavailable for setSelection.\"}";

    final Object br = bridge;
    final String bp = stripPrefix(effectivePath);
    return runOnEdt(
        () -> {
          // setSelection(List)
          Method method = findMethod(br.getClass(), "setSelection", 1);
          if (method != null) {
            try {
              method.setAccessible(true);
              List<String> selection = new ArrayList<>();
              selection.add(bp);
              method.invoke(br, selection);
              return "Component selected on canvas";
            } catch (Exception e) {
              log.debug("setSelection(List) failed: {}", e.getMessage());
            }
          }

          // setDeepSelection(String)
          Method deep = findMethod(br.getClass(), "setDeepSelection", 1);
          if (deep != null && deep.getParameterTypes()[0] == String.class) {
            try {
              deep.setAccessible(true);
              deep.invoke(br, bp);
              return "Component selected via setDeepSelection";
            } catch (Exception e) {
              log.debug("setDeepSelection failed: {}", e.getMessage());
            }
          }

          String raw = sendRaw(br, "setSelection", new Gson().toJson(new String[] {bp}));
          if (raw != null) return "Component selected via sendRaw: " + raw;
          return "{\"error\": \"setSelection failed.\"}";
        });
  }

  // =====================================================================
  //  Diagnostics
  // =====================================================================

  public String getDiagnostics() {
    StringBuilder sb = new StringBuilder("=== AI Designer — Bridge Diagnostics ===\n\n");

    // Module hook
    try {
      Object hook = context.getModule(PERSPECTIVE_MODULE_ID);
      sb.append("[Hook] ").append(hook != null ? hook.getClass().getName() : "NULL").append("\n");
    } catch (Exception e) {
      sb.append("[Hook] Error: ").append(e.getMessage()).append("\n");
    }

    // Workspace
    cachedWorkspace = null; // Force fresh search
    Object workspace = findPerspectiveWorkspace();
    sb.append("[Workspace] ")
        .append(workspace != null ? workspace.getClass().getName() : "NOT FOUND")
        .append("\n");

    // Active editor
    if (workspace != null) {
      Object editor = findActiveEditor(workspace);
      sb.append("[Active Editor] ")
          .append(editor != null ? editor.getClass().getName() : "NOT FOUND")
          .append("\n");
    }

    // Bridge
    Object bridge = findActiveBridge();
    sb.append("[Bridge] ")
        .append(bridge != null ? bridge.getClass().getName() : "NOT FOUND")
        .append("\n");
    if (bridge != null) {
      sb.append("\nBridge methods:\n");
      for (Method m : bridge.getClass().getMethods()) {
        if (m.getDeclaringClass() == Object.class) continue;
        String[] ptNames =
            Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName).toArray(String[]::new);
        sb.append("  ")
            .append(m.getReturnType().getSimpleName())
            .append(" ")
            .append(m.getName())
            .append("(")
            .append(String.join(", ", ptNames))
            .append(")\n");
      }
    }

    // DockingManager frames
    sb.append("\n[DockingManager Frames]\n");
    try {
      var dm = context.getDockingManager();
      if (dm != null && dm.getAllFrameNames() != null) {
        for (var name : dm.getAllFrameNames()) sb.append("  ").append(name).append("\n");
      }
    } catch (Exception e) {
      sb.append("  Error: ").append(e.getMessage()).append("\n");
    }

    return sb.toString();
  }

  // =====================================================================
  //  Helpers
  // =====================================================================

  private String pathError(String path) {
    return "{\"error\": \"Could not resolve component path '"
        + path
        + "'. "
        + "Use read_view to see the component tree and use numeric index paths like 'D.0:1:2'.\"}";
  }

  private void logBridgeMethods(Object bridge) {
    if (!log.isInfoEnabled()) return;
    String cn = bridge.getClass().getName();
    if (cn.equals(lastLoggedBridgeClass)) return;
    lastLoggedBridgeClass = cn;
    log.info("Bridge methods for {}:", cn);
    for (Method m : bridge.getClass().getMethods()) {
      if (m.getDeclaringClass() == Object.class) continue;
      String[] ptNames =
          Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName).toArray(String[]::new);
      log.info(
          "  {}({}) -> {}",
          m.getName(),
          String.join(", ", ptNames),
          m.getReturnType().getSimpleName());
    }
  }
}
