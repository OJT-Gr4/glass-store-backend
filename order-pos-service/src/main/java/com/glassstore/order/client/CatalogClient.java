package com.glassstore.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** Talks to catalog-service, which owns stock data. */
@FeignClient(configuration = ServiceClientSecurity.class, name = "catalog-service", url = "${services.catalog-service.url}")
public interface CatalogClient {

    @GetMapping("/api/variants/sku/{sku}")
    CatalogContracts.ApiEnvelope<CatalogContracts.VariantInfo> getBySku(@PathVariable("sku") String sku);

    @GetMapping("/api/variants/barcode/{barcode}")
    CatalogContracts.ApiEnvelope<CatalogContracts.VariantInfo> getByBarcode(@PathVariable("barcode") String barcode);

    @GetMapping("/api/variants/search")
    CatalogContracts.ApiEnvelope<java.util.List<CatalogContracts.VariantInfo>> search(@RequestParam("q") String keyword);

    @PostMapping("/api/variants/reserve")
    CatalogContracts.ApiEnvelope<CatalogContracts.ReservationResult> reserve(
            @RequestBody CatalogContracts.ReserveRequest request);

    @PostMapping("/api/variants/release")
    CatalogContracts.ApiEnvelope<CatalogContracts.ReservationResult> release(
            @RequestBody CatalogContracts.ReleaseRequest request);

    @PostMapping("/api/variants/commit")
    CatalogContracts.ApiEnvelope<CatalogContracts.ReservationResult> commit(
            @RequestBody CatalogContracts.CommitRequest request);
}