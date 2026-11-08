package com.glassstore.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable order measurements; source distinguishes a clinical revision from customer input. */
@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderPrescription {
    @Column(name = "rx_version") private Long version;
    @Column(name = "rx_od_sph", precision = 4, scale = 2) private BigDecimal odSph;
    @Column(name = "rx_od_cyl", precision = 4, scale = 2) private BigDecimal odCyl;
    @Column(name = "rx_od_axis") private Integer odAxis;
    @Column(name = "rx_od_add", precision = 4, scale = 2) private BigDecimal odAdd;
    @Column(name = "rx_os_sph", precision = 4, scale = 2) private BigDecimal osSph;
    @Column(name = "rx_os_cyl", precision = 4, scale = 2) private BigDecimal osCyl;
    @Column(name = "rx_os_axis") private Integer osAxis;
    @Column(name = "rx_os_add", precision = 4, scale = 2) private BigDecimal osAdd;
    @Column(name = "rx_pd", precision = 4, scale = 1) private BigDecimal pd;
    @Column(name = "rx_source", length = 30) private String source;
    @Column(name = "rx_customer_confirmed_at") private LocalDateTime customerConfirmedAt;
}
