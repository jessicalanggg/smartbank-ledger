package com.smartbank.events;

import com.smartbank.auth.AccessService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhook-endpoints")
public class WebhookController {
    private final WebhookEndpointService endpoints;
    private final AccessService access;

    public WebhookController(WebhookEndpointService endpoints, AccessService access) {
        this.endpoints = endpoints;
        this.access = access;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookEndpointService.EndpointResponse create(@RequestBody WebhookEndpointService.CreateRequest request) {
        return endpoints.create(access.currentUserId(), request);
    }

    @GetMapping
    public List<WebhookEndpointService.EndpointResponse> list() {
        return endpoints.list(access.currentUserId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID id) {
        endpoints.deactivate(access.currentUserId(), id);
    }

    /** {@code overlapSeconds}: how long the old secret keeps signing (default 24 h, 0 to stop at once). */
    @PostMapping("/{id}/rotate-secret")
    public WebhookEndpointService.RotatedSecret rotateSecret(@PathVariable UUID id,
            @RequestParam(defaultValue = "86400") long overlapSeconds) {
        return endpoints.rotateSecret(access.currentUserId(), id, java.time.Duration.ofSeconds(overlapSeconds));
    }

    @PostMapping("/{id}/deliveries/{deliveryId}/resend")
    public WebhookEndpointService.DeliveryResponse resend(@PathVariable UUID id, @PathVariable UUID deliveryId) {
        return endpoints.resend(access.currentUserId(), id, deliveryId);
    }

    @PostMapping("/{id}/deliveries/resend-failed")
    public java.util.Map<String, Integer> resendFailed(@PathVariable UUID id) {
        return java.util.Map.of("queued", endpoints.resendFailed(access.currentUserId(), id));
    }

    @GetMapping("/{id}/deliveries")
    public List<WebhookEndpointService.DeliveryResponse> deliveries(@PathVariable UUID id) {
        return endpoints.deliveries(access.currentUserId(), id);
    }
}
