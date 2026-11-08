package com.glassstore.lab.service;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.lab.client.WarrantyCatalogClient;
import com.glassstore.lab.client.WarrantyOrderClient;
import com.glassstore.lab.dto.CatalogVariantInfo;
import com.glassstore.lab.dto.WarrantyActivationRequest;
import com.glassstore.lab.dto.WarrantyOrderItemDTO;
import com.glassstore.lab.entity.Warranty;
import com.glassstore.lab.repository.WarrantyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarrantyServiceTest {

    private WarrantyRepository warrantyRepository;
    private WarrantyOrderClient orderClient;
    private WarrantyCatalogClient catalogClient;
    private WarrantyService service;

    @BeforeEach
    void setUp() {
        warrantyRepository = mock(WarrantyRepository.class);
        orderClient = mock(WarrantyOrderClient.class);
        catalogClient = mock(WarrantyCatalogClient.class);
        service = new WarrantyService(warrantyRepository, orderClient, catalogClient);
        when(orderClient.getPurchaseItem(5L, 19L)).thenReturn(new WarrantyOrderItemDTO(
                5L, 19L, "ORD-5", 8L, "Khách hàng", "0901234567",
                "LNS-COAT-1", "Lens coating", LocalDateTime.now().minusDays(2),
                "PAID", "LENS_COATING", "PENDING", 0, null));
        when(catalogClient.findBySku("LNS-COAT-1")).thenReturn(
                ApiResponse.<CatalogVariantInfo>builder()
                        .data(new CatalogVariantInfo("LNS-COAT-1", "89300001", "Lens coating"))
                        .build());
    }

    @Test
    void activatesLensBenefitWithThirtyDayExpiryFromPurchaseDate() {
        Warranty saved = Warranty.builder().id(1L).orderItemId(19L).benefitType("LENS_COATING").build();
        when(warrantyRepository.findByOrderItemIdAndBenefitType(19L, "LENS_COATING"))
                .thenReturn(Optional.empty(), Optional.of(saved));

        Warranty result = service.activate(new WarrantyActivationRequest(5L, 19L, "LENS_COATING"));

        assertThat(result.getId()).isEqualTo(1L);
        verify(warrantyRepository).insertIfMissing(
                anyString(), eq("ORD-5"), eq(5L), eq(19L), eq("LENS_COATING"),
                any(LocalDateTime.class), any(LocalDateTime.class), eq("89300001"),
                eq(8L), eq("Khách hàng"), eq("0901234567"), eq("Lens coating"),
                eq("LNS-COAT-1"), anyString(), eq("LENS_COATING_30_DAYS"),
                anyString(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void repeatedActivationReturnsExistingWarrantyWithoutInsertingAgain() {
        Warranty existing = Warranty.builder().id(2L).orderItemId(19L).benefitType("LENS_COATING").build();
        when(warrantyRepository.findByOrderItemIdAndBenefitType(19L, "LENS_COATING"))
                .thenReturn(Optional.of(existing));

        assertThat(service.activate(new WarrantyActivationRequest(5L, 19L, "LENS_COATING")))
                .isSameAs(existing);
        verify(warrantyRepository, never()).insertIfMissing(
                anyString(), anyString(), any(), any(), anyString(), any(), any(), any(),
                any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void exchangePolicyExpiresThirtyDaysAfterTheActualPurchase() {
        Warranty saved = Warranty.builder().id(9L).orderItemId(19L).benefitType("EXCHANGE_30_DAYS").build();
        when(warrantyRepository.findByOrderItemIdAndBenefitType(19L,"EXCHANGE_30_DAYS"))
                .thenReturn(Optional.empty(),Optional.of(saved));
        service.activate(new WarrantyActivationRequest(5L,19L,"EXCHANGE_30_DAYS"));
        var expiry = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(warrantyRepository).insertIfMissing(
                anyString(),eq("ORD-5"),eq(5L),eq(19L),eq("EXCHANGE_30_DAYS"),
                any(LocalDateTime.class),expiry.capture(),eq("89300001"),eq(8L),eq("Khách hàng"),eq("0901234567"),eq("Lens coating"),
                eq("LNS-COAT-1"),anyString(),eq("EXCHANGE_30_DAYS"),anyString(),anyString(),any(LocalDateTime.class),any(LocalDateTime.class));
        assertThat(expiry.getValue()).isEqualTo(orderClient.getPurchaseItem(5L,19L).purchaseDate().plusDays(30));
    }

    @Test
    void policyVersioningSnapshotsNewDurationWithoutMutatingPreviouslyActivatedWarranties() {
        var policyRepo = mock(com.glassstore.lab.repository.WarrantyPolicyRepository.class);
        var versionedService = new WarrantyService(warrantyRepository, orderClient, catalogClient, policyRepo);

        var oldPolicy = com.glassstore.lab.entity.WarrantyPolicy.builder()
                .policyCode("LENS_COATING_30_DAYS")
                .benefitType("LENS_COATING")
                .version(1)
                .durationDays(30)
                .coverageTerms("Bảo hành lớp phủ 30 ngày")
                .active(true)
                .build();
        when(policyRepo.findFirstByBenefitTypeAndActiveTrueOrderByVersionDesc("LENS_COATING"))
                .thenReturn(Optional.of(oldPolicy));
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var newPolicy = versionedService.savePolicyVersion(
                "LENS_COATING", 60, "Bảo hành lớp phủ nâng cấp 60 ngày", java.math.BigDecimal.ZERO, "admin");
        assertThat(newPolicy.getVersion()).isEqualTo(2);
        assertThat(newPolicy.getDurationDays()).isEqualTo(60);
        assertThat(oldPolicy.isActive()).isFalse();

        when(policyRepo.findFirstByBenefitTypeAndActiveTrueOrderByVersionDesc("LENS_COATING"))
                .thenReturn(Optional.of(newPolicy));
        Warranty savedV2 = Warranty.builder().id(11L).orderItemId(19L).benefitType("LENS_COATING").policyRef("LENS_COATING_30_DAYS_V2").build();
        when(warrantyRepository.findByOrderItemIdAndBenefitType(19L, "LENS_COATING"))
                .thenReturn(Optional.empty(), Optional.of(savedV2));

        Warranty activated = versionedService.activate(new WarrantyActivationRequest(5L, 19L, "LENS_COATING"));
        assertThat(activated.getPolicyRef()).isEqualTo("LENS_COATING_30_DAYS_V2");

        var expiry = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(warrantyRepository).insertIfMissing(
                anyString(), eq("ORD-5"), eq(5L), eq(19L), eq("LENS_COATING"),
                any(LocalDateTime.class), expiry.capture(), eq("89300001"),
                eq(8L), eq("Khách hàng"), eq("0901234567"), eq("Lens coating"),
                eq("LNS-COAT-1"), eq("Bảo hành lớp phủ nâng cấp 60 ngày"), eq("LENS_COATING_30_DAYS_V2"),
                anyString(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
        assertThat(expiry.getValue()).isEqualTo(orderClient.getPurchaseItem(5L, 19L).purchaseDate().plusDays(60));
    }

    @Test
    void rejectsBenefitThatDoesNotMatchPurchasedItem() {
        assertThatThrownBy(() -> service.activate(
                new WarrantyActivationRequest(5L, 19L, "FRAME_ADJUSTMENT")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(warrantyRepository, never()).insertIfMissing(
                anyString(), anyString(), any(), any(), anyString(), any(), any(), any(),
                any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any());
    }
}
