package com.glassstore.order.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.SalesChannel;
import com.glassstore.order.entity.Voucher;
import com.glassstore.order.repository.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoucherServiceTest {

    private VoucherRepository repository;
    private VoucherService service;

    @BeforeEach
    void setUp() {
        repository = mock(VoucherRepository.class);
        service = new VoucherService(repository);
    }

    @Test
    @DisplayName("Blank voucher code yields zero discount")
    void blankIsZero() {
        assertThat(service.calculateDiscount(" ", new BigDecimal("100000"), SalesChannel.POS))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("PERCENT discount is capped by maxDiscountAmount (BR-20)")
    void percentCapped() {
        Voucher voucher = Voucher.builder()
                .code("SALE50")
                .name("Sale")
                .active(true)
                .discountType("PERCENT")
                .discountValue(new BigDecimal("50"))
                .maxDiscountAmount(new BigDecimal("20000"))
                .channel("ALL")
                .build();
        when(repository.findByCodeIgnoreCase("SALE50")).thenReturn(Optional.of(voucher));

        assertThat(service.calculateDiscount("SALE50", new BigDecimal("100000"), SalesChannel.ONLINE))
                .isEqualByComparingTo("20000.00");
    }

    @Test
    @DisplayName("Expired voucher is rejected (BR-19)")
    void expired() {
        Voucher voucher = Voucher.builder()
                .code("OLD")
                .name("Old")
                .active(true)
                .discountType("FIXED")
                .discountValue(new BigDecimal("10000"))
                .validTo(LocalDateTime.now().minusDays(1))
                .channel("ALL")
                .build();
        when(repository.findByCodeIgnoreCase("OLD")).thenReturn(Optional.of(voucher));

        assertThatThrownBy(() -> service.calculateDiscount("OLD", new BigDecimal("50000"), SalesChannel.POS))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VOUCHER_EXPIRED);
    }

    @Test
    @DisplayName("Min order amount is enforced")
    void minOrder() {
        Voucher voucher = Voucher.builder()
                .code("MIN")
                .name("Min")
                .active(true)
                .discountType("FIXED")
                .discountValue(new BigDecimal("10000"))
                .minOrderAmount(new BigDecimal("200000"))
                .channel("ALL")
                .build();
        when(repository.findByCodeIgnoreCase("MIN")).thenReturn(Optional.of(voucher));

        assertThatThrownBy(() -> service.calculateDiscount("MIN", new BigDecimal("100000"), SalesChannel.POS))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VOUCHER_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("Unknown code is not guessed")
    void unknown() {
        when(repository.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.calculateDiscount("NOPE", new BigDecimal("1"), SalesChannel.ONLINE))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VOUCHER_NOT_FOUND);
    }

    @Test
    @DisplayName("Admin CRUD: create, toggle, and list vouchers")
    void adminVoucherLifecycle() {
        Voucher newVoucher = Voucher.builder()
                .code("NEWCODE")
                .name("New Program")
                .discountType("PERCENT")
                .discountValue(new BigDecimal("10"))
                .active(true)
                .build();
        when(repository.findByCodeIgnoreCase("NEWCODE")).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any(Voucher.class))).thenAnswer(i -> i.getArgument(0));

        Voucher created = service.createVoucher(newVoucher);
        assertThat(created.getCode()).isEqualTo("NEWCODE");
        assertThat(created.isActive()).isTrue();

        when(repository.findById(1L)).thenReturn(Optional.of(created));
        Voucher toggled = service.toggleActive(1L);
        assertThat(toggled.isActive()).isFalse();

        when(repository.findAll()).thenReturn(java.util.List.of(toggled));
        assertThat(service.getAllVouchers()).hasSize(1);
    }
}
