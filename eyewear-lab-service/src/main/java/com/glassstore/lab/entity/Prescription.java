package com.glassstore.lab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "prescriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Prescription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Also identifies the immutable revision pinned by orders and lab jobs. */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "optometrist_id", nullable = false)
    private Long optometristId;

    @Column(name = "optometrist_name")
    private String optometristName;

    @Column(name = "od_sph", precision = 4, scale = 2, nullable = false)
    private BigDecimal odSph;

    @Column(name = "od_cyl", precision = 4, scale = 2)
    private BigDecimal odCyl;

    @Column(name = "od_axis")
    private Integer odAxis;

    @Column(name = "od_add", precision = 4, scale = 2)
    private BigDecimal odAdd;

    @Column(name = "os_sph", precision = 4, scale = 2, nullable = false)
    private BigDecimal osSph;

    @Column(name = "os_cyl", precision = 4, scale = 2)
    private BigDecimal osCyl;

    @Column(name = "os_axis")
    private Integer osAxis;

    @Column(name = "os_add", precision = 4, scale = 2)
    private BigDecimal osAdd;

    @Column(name = "pd", precision = 4, scale = 1, nullable = false)
    private BigDecimal pd;

    @Column(length = 500)
    private String notes;

    @Column(name = "lens_sku")
    private String lensSku;

    @Column(name = "lens_name")
    private String lensName;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

