package com.glassstore.lab.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.Data;
@Data
public class CreatePrescriptionRequest {
    @NotNull @Positive
    private Long customerId;
    @NotNull @DecimalMin("-30") @DecimalMax("30") @Digits(integer=2,fraction=2)
    private BigDecimal odSph;
    @NotNull @DecimalMin("-10") @DecimalMax("10") @Digits(integer=2,fraction=2)
    private BigDecimal odCyl;
    @NotNull @Min(0) @Max(180)
    private Integer odAxis;
    @NotNull @DecimalMin("0") @DecimalMax("4") @Digits(integer=1,fraction=2)
    private BigDecimal odAdd;
    @NotNull @DecimalMin("-30") @DecimalMax("30") @Digits(integer=2,fraction=2)
    private BigDecimal osSph;
    @NotNull @DecimalMin("-10") @DecimalMax("10") @Digits(integer=2,fraction=2)
    private BigDecimal osCyl;
    @NotNull @Min(0) @Max(180)
    private Integer osAxis;
    @NotNull @DecimalMin("0") @DecimalMax("4") @Digits(integer=1,fraction=2)
    private BigDecimal osAdd;
    @NotNull @DecimalMin("40") @DecimalMax("90") @Digits(integer=2,fraction=1)
    private BigDecimal pd;
    @Size(max=500)
    private String notes;
    @Size(max=255)
    private String lensSku;
    @Size(max=255)
    private String lensName;
}
