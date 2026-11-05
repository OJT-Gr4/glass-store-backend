package com.glassstore.order.client;

import com.glassstore.order.dto.WarrantyActivationRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(configuration = ServiceClientSecurity.class, name = "eyewear-lab-warranty", url = "${services.eyewear-lab-service.url}")
public interface WarrantyClient {

    @PostMapping("/api/warranties/activate")
    Map<String, Object> activate(@RequestBody WarrantyActivationRequest request);
}
