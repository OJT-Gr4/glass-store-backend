package com.glassstore.lab.controller;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

class PrescriptionConcurrentUpdateTest extends PrescriptionRevisionTestSupport {
    @Test
    void simultaneousEditorsCommitExactlyOneRevisionAndReturnConflictToTheOther() throws Exception {
        long id = create();
        // Both HTTP requests have loaded the same version before identity verification returns.
        CyclicBarrier bothLoaded = new CyclicBarrier(2);
        when(customers.requireCustomer(customerId)).thenAnswer(call -> {
            bothLoaded.await(10, TimeUnit.SECONDS);
            return "Verified Customer";
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> mvc.perform(patch("/api/prescriptions/" + id)
                            .with(actor(202, "OPTOMETRIST")).contentType("application/json")
                            .content(update(0, -3.25).toString()))
                    .andReturn().getResponse().getStatus());
            var second = executor.submit(() -> mvc.perform(patch("/api/prescriptions/" + id)
                            .with(actor(303, "OPTOMETRIST")).contentType("application/json")
                            .content(update(0, -4.50).toString()))
                    .andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        var current = prescriptions.findById(id).orElseThrow();
        assertThat(current.getVersion()).isEqualTo(1L);
        var history = revisions.findByPrescriptionIdOrderByVersionDesc(id);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getOdSph()).isEqualByComparingTo(current.getOdSph());
        assertThat(history.get(0).getActorId()).isIn(202L, 303L);
        assertThat(history.get(1).getVersion()).isZero();
        assertThat(history.get(1).getOdSph()).isEqualByComparingTo("-2.00");
    }
}
