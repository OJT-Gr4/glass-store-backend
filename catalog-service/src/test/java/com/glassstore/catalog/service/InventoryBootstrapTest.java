package com.glassstore.catalog.service;

import com.glassstore.catalog.config.InventoryDataInitializer;
import com.glassstore.catalog.entity.*;
import com.glassstore.catalog.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InventoryBootstrapTest {
    @Test
    void freshStockIsDistributedWithoutInflationAndRestartPreservesBalances() {
        var locations = mock(InventoryLocationRepository.class);
        var balances = mock(InventoryBalanceRepository.class);
        var variants = mock(ProductVariantRepository.class);
        var ids = new AtomicLong();
        var locationMap = new HashMap<String, InventoryLocation>();
        var balanceMap = new HashMap<String, InventoryBalance>();
        when(locations.findByCode(anyString())).thenAnswer(call -> Optional.ofNullable(locationMap.get(call.getArgument(0))));
        when(locations.save(any())).thenAnswer(call -> {
            InventoryLocation item = call.getArgument(0); item.setId(ids.incrementAndGet()); locationMap.put(item.getCode(), item); return item;
        });
        when(balances.existsByVariant_IdAndLocation_Id(anyLong(), anyLong())).thenAnswer(call ->
                balanceMap.containsKey(call.getArgument(0) + ":" + call.getArgument(1)));
        when(balances.save(any())).thenAnswer(call -> {
            InventoryBalance item = call.getArgument(0); balanceMap.put(item.getVariant().getId() + ":" + item.getLocation().getId(), item); return item;
        });
        var first = ProductVariant.builder().id(1L).stockQuantity(12).build();
        var small = ProductVariant.builder().id(2L).stockQuantity(4).build();
        when(variants.findAll()).thenReturn(List.of(first, small));
        var initializer = new InventoryDataInitializer(locations, balances, variants);
        ReflectionTestUtils.setField(initializer, "initialBranchQuantity", 5);
        initializer.initializeInventoryLocationsAndLegacyBalances();
        assertEquals(6, balanceMap.size());
        assertEquals(16, balanceMap.values().stream().mapToInt(InventoryBalance::getQuantity).sum());
        var firstBranch = balanceMap.values().stream().filter(b -> b.getVariant() == first && b.getLocation().getCode().equals("BRANCH_01")).findFirst().orElseThrow();
        assertEquals(5, firstBranch.getQuantity());
        assertTrue(balanceMap.values().stream().allMatch(b -> b.getQuantity() >= 0));
        firstBranch.setQuantity(2);
        first.setStockQuantity(9);
        initializer.initializeInventoryLocationsAndLegacyBalances();
        assertEquals(6, balanceMap.size());
        assertEquals(2, firstBranch.getQuantity(), "Restart must not restock an existing location");
    }
}
