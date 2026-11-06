package com.glassstore.catalog.config;

import com.glassstore.catalog.entity.InventoryBalance;
import com.glassstore.catalog.entity.InventoryLocation;
import com.glassstore.catalog.entity.ProductVariant;
import com.glassstore.catalog.repository.InventoryBalanceRepository;
import com.glassstore.catalog.repository.InventoryLocationRepository;
import com.glassstore.catalog.repository.ProductVariantRepository;
import com.glassstore.catalog.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryDataInitializer {

    @org.springframework.beans.factory.annotation.Value("${glassstore.inventory.initial-branch-quantity:0}")
    private int initialBranchQuantity;

    private final InventoryLocationRepository locationRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final ProductVariantRepository variantRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initializeInventoryLocationsAndLegacyBalances() {
        List<InventoryLocation> locations = List.of(
                location(InventoryService.BRANCH_01, "Chi nhánh 01"),
                location(InventoryService.BRANCH_02, "Chi nhánh 02"),
                location(InventoryService.LAB, "Tổng kho Lab")
        ).stream().map(this::saveIfMissing).toList();

        for (ProductVariant variant : variantRepository.findAll()) {
            boolean hasBalance = locations.stream()
                    .anyMatch(location -> balanceRepository.existsByVariant_IdAndLocation_Id(
                            variant.getId(), location.getId()));
            for (InventoryLocation location : locations) {
                if (!balanceRepository.existsByVariant_IdAndLocation_Id(variant.getId(), location.getId())) {
                    int total = Math.max(0, variant.getStockQuantity());
                    int firstBranch = Math.min(Math.max(0, initialBranchQuantity), total);
                    int secondBranch = Math.min(Math.max(0, initialBranchQuantity), total - firstBranch);
                    int initialQuantity = hasBalance ? 0 : switch (location.getCode()) {
                        case InventoryService.BRANCH_01 -> firstBranch;
                        case InventoryService.BRANCH_02 -> secondBranch;
                        default -> total - firstBranch - secondBranch;
                    };
                    balanceRepository.save(InventoryBalance.builder()
                            .variant(variant)
                            .location(location)
                            .quantity(initialQuantity)
                            .build());
                }
            }
        }
        log.info("Inventory locations initialized according to bootstrap policy; existing balances preserved.");
    }

    private InventoryLocation location(String code, String name) {
        return InventoryLocation.builder().code(code).name(name).active(true).build();
    }

    private InventoryLocation saveIfMissing(InventoryLocation location) {
        return locationRepository.findByCode(location.getCode())
                .orElseGet(() -> locationRepository.save(location));
    }
}
