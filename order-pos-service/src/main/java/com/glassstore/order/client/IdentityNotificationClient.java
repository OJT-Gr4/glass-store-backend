package com.glassstore.order.client;

import com.glassstore.order.dto.NotificationEventPayload;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(
        configuration = ServiceClientSecurity.class,
        name = "identity-notifications",
        url = "${services.identity-service.url}"
)
public interface IdentityNotificationClient {

    @PostMapping("/internal/notifications/events")
    Map<String, Object> publishEvent(@RequestBody NotificationEventPayload payload);
}
