package com.glassstore.lab.client;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.lab.dto.CatalogVariantInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(configuration = ServiceClientSecurity.class, name = "catalog-warranty-lookup", url = "${services.catalog-service.url}")
public interface WarrantyCatalogClient {

    @GetMapping("/api/variants/barcode/{barcode}")
    ApiResponse<CatalogVariantInfo> findByBarcode(@PathVariable("barcode") String barcode);

    @GetMapping("/api/variants/sku/{sku}")
    ApiResponse<CatalogVariantInfo> findBySku(@PathVariable("sku") String sku);
}
