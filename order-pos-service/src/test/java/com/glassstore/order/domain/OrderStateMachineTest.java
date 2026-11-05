package com.glassstore.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order state machine rules (SRS §4.6, BR-14, BR-17).
 *
 * <p>These are the guarantees that make "arbitrary status writes" impossible, so they are
 * tested directly rather than only through the HTTP layer.</p>
 */
class OrderStateMachineTest {

    @Test
    @DisplayName("Happy path: pending -> paid -> confirmed -> preparing -> shipping -> completed")
    void happyPath() {
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.PENDING_CONFIRMATION)).isTrue();
        assertThat(OrderStatus.PENDING_CONFIRMATION.canTransitionTo(OrderStatus.CONFIRMED)).isTrue();
        assertThat(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.PREPARING)).isTrue();
        assertThat(OrderStatus.PREPARING.canTransitionTo(OrderStatus.SHIPPING)).isTrue();
        assertThat(OrderStatus.SHIPPING.canTransitionTo(OrderStatus.COMPLETED)).isTrue();
    }

    @Test
    @DisplayName("A completed order is terminal and cannot be edited (BR-17)")
    void completedIsTerminal() {
        assertThat(OrderStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(OrderStatus.COMPLETED.canTransitionTo(OrderStatus.SHIPPING)).isFalse();
        assertThat(OrderStatus.COMPLETED.canTransitionTo(OrderStatus.PAID)).isFalse();
    }

    @Test
    @DisplayName("Payment cannot be skipped: pending -> completed is rejected")
    void cannotSkipPayment() {
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.COMPLETED)).isFalse();
        assertThatThrownBy(() -> OrderStateMachine.requireTransition(
                OrderStatus.PENDING_PAYMENT, OrderStatus.COMPLETED))
                .isInstanceOf(com.glassstore.common.exception.ApiException.class)
                .hasMessageContaining("Không thể chuyển");
    }

    @Test
    @DisplayName("Refund path is one-way: refund pending -> refunded")
    void refundPath() {
        assertThat(OrderStatus.REFUND_PENDING.canTransitionTo(OrderStatus.REFUNDED)).isTrue();
        assertThat(OrderStatus.REFUNDED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("Customer may cancel before preparation, not after (OQ-10)")
    void customerCancellationWindow() {
        assertThat(OrderStatus.PENDING_PAYMENT.isCustomerCancellable()).isTrue();
        assertThat(OrderStatus.PAID.isCustomerCancellable()).isTrue();
        assertThat(OrderStatus.CONFIRMED.isCustomerCancellable()).isTrue();
        assertThat(OrderStatus.PREPARING.isCustomerCancellable()).isFalse();
        assertThat(OrderStatus.SHIPPING.isCustomerCancellable()).isFalse();
    }

    @Test
    @DisplayName("Legacy PROCESSING rows still load and map to PREPARING")
    void legacyStatusParsing() {
        assertThat(OrderStatus.parse("PROCESSING")).isEqualTo(OrderStatus.PREPARING);
        assertThat(OrderStatus.parse("paid")).isEqualTo(OrderStatus.PAID);
        assertThatThrownBy(() -> OrderStatus.parse("NOT_A_STATUS"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("OMS permits preparation after payment; paid cancellations require reconciliation")
    void omsStandardWorkflow() {
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.PREPARING)).isTrue();
        assertThat(OrderStatus.PREPARING.canTransitionTo(OrderStatus.SHIPPING)).isTrue();
        assertThat(OrderStatus.SHIPPING.canTransitionTo(OrderStatus.COMPLETED)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.PREPARING.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.SHIPPING.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCEL_PENDING)).isTrue();
        assertThat(OrderStatus.PREPARING.canTransitionTo(OrderStatus.REFUND_PENDING)).isTrue();
    }

    @Test
    @DisplayName("A paid order reports as paid-like so it must use the refund path (BR-18)")
    void paidLikeDetection() {
        assertThat(OrderStatus.PAID.isPaidLike()).isTrue();
        assertThat(OrderStatus.PREPARING.isPaidLike()).isTrue();
        assertThat(OrderStatus.PENDING_PAYMENT.isPaidLike()).isFalse();
    }
}
