package com.glassstore.catalog.service;

import com.glassstore.catalog.dto.CatalogManagementDtos.CategoryRequest;
import com.glassstore.catalog.dto.CatalogManagementDtos.BrandRequest;
import com.glassstore.catalog.dto.CatalogManagementDtos.LocationActiveRequest;
import com.glassstore.catalog.dto.CatalogManagementDtos.LocationCreateRequest;
import com.glassstore.catalog.entity.Category;
import com.glassstore.catalog.entity.InventoryLocation;
import com.glassstore.catalog.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogManagementServiceTest {

    @Mock CategoryRepository categoryRepository;
    @Mock BrandRepository brandRepository;
    @Mock InventoryLocationRepository locationRepository;
    @Mock ProductRepository productRepository;
    @Mock InventoryBalanceRepository balanceRepository;
    @Mock StockTransferRepository transferRepository;
    @Mock ProductVariantRepository variantRepository;
    @Mock LabAppointmentReferenceClient appointmentReferenceClient;
    @Mock OrderLocationReferenceClient orderReferenceClient;

    @InjectMocks CatalogManagementService service;

    @Test
    void categoryCreationTrimsFieldsAndRejectsDuplicateName() {
        when(categoryRepository.existsByNameIgnoreCase("Frames")).thenReturn(false);
        when(categoryRepository.existsBySlugIgnoreCase("frames")).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(call -> call.getArgument(0));

        var created = service.createCategory(new CategoryRequest(" Frames ", " frames ", " description "));

        assertEquals("Frames", created.name());
        assertEquals("frames", created.slug());
        assertEquals("description", created.description());

        when(categoryRepository.existsByNameIgnoreCase("Frames")).thenReturn(true);
        var duplicate = assertThrows(ResponseStatusException.class,
                () -> service.createCategory(new CategoryRequest("Frames", "frames-copy", null)));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
    }

    @Test
    void referencedCategoryCannotBeDeleted() {
        when(categoryRepository.findById(4L)).thenReturn(Optional.of(Category.builder().id(4L).build()));
        when(productRepository.existsByCategory_Id(4L)).thenReturn(true);

        var error = assertThrows(ResponseStatusException.class, () -> service.deleteCategory(4L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    void duplicateBrandNameAndLocationCodeAreRejected() {
        when(brandRepository.existsByNameIgnoreCase("Ray-Ban")).thenReturn(true);
        var duplicateBrand = assertThrows(ResponseStatusException.class,
                () -> service.createBrand(new BrandRequest("Ray-Ban", null, null)));
        assertEquals(HttpStatus.CONFLICT, duplicateBrand.getStatusCode());

        when(locationRepository.existsByCodeIgnoreCase("BRANCH_01")).thenReturn(true);
        var duplicateLocation = assertThrows(ResponseStatusException.class,
                () -> service.createLocation(new LocationCreateRequest(
                        "BRANCH_01", "Another location", null, "BRANCH")));
        assertEquals(HttpStatus.CONFLICT, duplicateLocation.getStatusCode());
        verify(locationRepository, never()).save(any(InventoryLocation.class));
    }

    @Test
    void locationCanBeDeactivatedWithoutDeletingItsInventoryIdentity() {
        InventoryLocation location = InventoryLocation.builder()
                .id(9L).code("BRANCH_01").name("Flagship").active(true).build();
        when(locationRepository.findById(9L)).thenReturn(Optional.of(location));
        when(locationRepository.save(location)).thenReturn(location);

        var updated = service.setLocationActive(9L, new LocationActiveRequest(false));

        assertFalse(updated.active());
        assertEquals("BRANCH_01", updated.code());
        assertEquals("Flagship", updated.name());
        verify(locationRepository, never()).delete(any(InventoryLocation.class));
    }

    @Test
    void locationWithBalanceCannotBeDeleted() {
        when(locationRepository.findById(9L)).thenReturn(Optional.of(
                InventoryLocation.builder().id(9L).code("BRANCH_01").name("Flagship").active(true).build()));
        when(balanceRepository.existsByLocation_Id(9L)).thenReturn(true);

        var error = assertThrows(ResponseStatusException.class, () -> service.deleteLocation(9L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(locationRepository, never()).delete(any(InventoryLocation.class));
    }

    @Test
    void locationReferencedByAppointmentCannotBeDeleted() {
        when(locationRepository.findById(9L)).thenReturn(Optional.of(
                InventoryLocation.builder().id(9L).code("BRANCH_01").name("Flagship").active(true).build()));
        when(appointmentReferenceClient.hasAppointmentsForLocation("BRANCH_01")).thenReturn(true);

        var error = assertThrows(ResponseStatusException.class, () -> service.deleteLocation(9L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(locationRepository, never()).delete(any(InventoryLocation.class));
    }

    @Test
    void locationReferencedByOrderCannotBeDeleted() {
        when(locationRepository.findById(9L)).thenReturn(Optional.of(
                InventoryLocation.builder().id(9L).code("BRANCH_01").name("Flagship").active(true).build()));
        when(orderReferenceClient.hasReferences("BRANCH_01", "Flagship")).thenReturn(true);

        var error = assertThrows(ResponseStatusException.class, () -> service.deleteLocation(9L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(locationRepository, never()).delete(any(InventoryLocation.class));
    }

    @Test
    void referencedBrandCannotBeDeleted() {
        when(brandRepository.findById(6L)).thenReturn(Optional.of(
                com.glassstore.catalog.entity.Brand.builder().id(6L).name("Ray-Ban").build()));
        when(productRepository.existsByBrand_Id(6L)).thenReturn(true);

        var error = assertThrows(ResponseStatusException.class, () -> service.deleteBrand(6L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(brandRepository, never()).delete(any(com.glassstore.catalog.entity.Brand.class));
    }

    @Test
    void creatingLocationCreatesZeroBalancesWithoutChangingVariantStock() {
        var variant = com.glassstore.catalog.entity.ProductVariant.builder()
                .sku("FRAME-001")
                .stockQuantity(12)
                .build();
        InventoryLocation savedLocation = InventoryLocation.builder()
                .id(9L).code("BRANCH_03").name("New branch").area("Central")
                .locationType("BRANCH").active(true).build();
        when(locationRepository.existsByCodeIgnoreCase("BRANCH_03")).thenReturn(false);
        when(locationRepository.existsByNameIgnoreCase("New branch")).thenReturn(false);
        when(locationRepository.save(any(InventoryLocation.class))).thenReturn(savedLocation);
        when(variantRepository.findAll()).thenReturn(java.util.List.of(variant));
        when(balanceRepository.saveAll(any())).thenAnswer(call -> call.getArgument(0));

        var created = service.createLocation(new LocationCreateRequest(
                "BRANCH_03", "New branch", "Central", "BRANCH"));

        assertEquals("BRANCH_03", created.code());
        assertEquals("Central", created.area());
        assertEquals("BRANCH", created.locationType());
        assertEquals(12, variant.getStockQuantity());
        verify(balanceRepository).saveAll(argThat(balances -> {
            var rows = new java.util.ArrayList<com.glassstore.catalog.entity.InventoryBalance>();
            balances.forEach(rows::add);
            return rows.size() == 1
                    && rows.get(0).getLocation() == savedLocation
                    && rows.get(0).getQuantity() == 0;
        }));
    }
}
