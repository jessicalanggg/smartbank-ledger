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

    @GetMapping("/{id}/deliveries")
    public List<WebhookEndpointService.DeliveryResponse> deliveries(@PathVariable UUID id) {
        return endpoints.deliveries(access.currentUserId(), id);
    }
}
