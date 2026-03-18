package com.edgerton.arc.designer.claude;

import com.google.gson.JsonElement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class PermissionPromptCoordinator {

  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

  private final Map<String, PendingPermissionRequest> pendingRequests = new ConcurrentHashMap<>();

  private volatile Consumer<PermissionRequest> onRequest;
  private volatile Consumer<ResolvedPermissionRequest> onResolution;

  public void setOnRequest(Consumer<PermissionRequest> onRequest) {
    this.onRequest = onRequest;
  }

  public void setOnResolution(Consumer<ResolvedPermissionRequest> onResolution) {
    this.onResolution = onResolution;
  }

  public String requestPermission(String toolName, JsonElement input) {
    Consumer<PermissionRequest> requestListener = onRequest;
    if (requestListener == null) {
      return PermissionPromptSupport.denyPayload("Permission prompt UI unavailable.");
    }

    JsonElement inputCopy = input == null ? null : input.deepCopy();
    PermissionPromptSupport.PermissionRequestDetails details =
        PermissionPromptSupport.describeRequest(toolName, inputCopy);
    PermissionRequest request =
        new PermissionRequest(
            UUID.randomUUID().toString(),
            toolName,
            details.title(),
            details.targetPath(),
            details.summary(),
            details.preview());
    PendingPermissionRequest pendingRequest = new PendingPermissionRequest(request, inputCopy);
    pendingRequests.put(request.requestId(), pendingRequest);

    requestListener.accept(request);

    PermissionDecision decision = waitForDecision(pendingRequest);
    pendingRequests.remove(request.requestId());

    Consumer<ResolvedPermissionRequest> resolutionListener = onResolution;
    if (resolutionListener != null) {
      resolutionListener.accept(
          new ResolvedPermissionRequest(
              request,
              decision.approved(),
              PermissionPromptSupport.approvalStatusMessage(
                  decision.approved(),
                  request.toolName(),
                  request.targetPath(),
                  decision.message())));
    }

    return decision.approved()
        ? PermissionPromptSupport.allowPayload(inputCopy)
        : PermissionPromptSupport.denyPayload(decision.message());
  }

  public boolean approveRequest(String requestId) {
    return completeRequest(requestId, true, "Approved by user.");
  }

  public boolean denyRequest(String requestId, String message) {
    return completeRequest(
        requestId, false, message == null || message.isBlank() ? "Denied by user." : message);
  }

  public void cancelPendingRequests(String message) {
    String denialMessage =
        message == null || message.isBlank() ? "Request canceled by user." : message;
    for (String requestId : pendingRequests.keySet()) {
      completeRequest(requestId, false, denialMessage);
    }
  }

  private PermissionDecision waitForDecision(PendingPermissionRequest pendingRequest) {
    try {
      if (pendingRequest.latch().await(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        return pendingRequest.decision();
      }
      completeRequest(pendingRequest.request().requestId(), false, "Permission request timed out.");
      return pendingRequest.decision();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      completeRequest(
          pendingRequest.request().requestId(), false, "Permission request interrupted.");
      return pendingRequest.decision();
    }
  }

  private boolean completeRequest(String requestId, boolean approved, String message) {
    PendingPermissionRequest pendingRequest = pendingRequests.get(requestId);
    if (pendingRequest == null) {
      return false;
    }
    return pendingRequest.complete(new PermissionDecision(approved, message));
  }

  public record PermissionRequest(
      String requestId,
      String toolName,
      String title,
      String targetPath,
      String summary,
      String preview) {}

  public record ResolvedPermissionRequest(
      PermissionRequest request, boolean approved, String statusMessage) {}

  private record PermissionDecision(boolean approved, String message) {}

  private static final class PendingPermissionRequest {
    private final PermissionRequest request;
    private final JsonElement input;
    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private volatile PermissionDecision decision =
        new PermissionDecision(false, "Permission request expired.");

    private PendingPermissionRequest(PermissionRequest request, JsonElement input) {
      this.request = request;
      this.input = input;
    }

    private boolean complete(PermissionDecision decision) {
      if (!completed.compareAndSet(false, true)) {
        return false;
      }
      this.decision = decision;
      latch.countDown();
      return true;
    }

    private PermissionRequest request() {
      return request;
    }

    @SuppressWarnings("unused")
    private JsonElement input() {
      return input;
    }

    private CountDownLatch latch() {
      return latch;
    }

    private PermissionDecision decision() {
      return decision;
    }
  }
}
