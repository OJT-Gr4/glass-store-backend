package com.glassstore.catalog.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class StockTransferDTO {
    private Long id;
    private String transferCode;
    private String sourceLocationCode;
    private String sourceLocationName;
    private String destinationLocationCode;
    private String destinationLocationName;
    private String reason;
    private String status;
    private String createdBy;
    private String dispatchedBy;
    private String receivedBy;
    private LocalDateTime createdAt;
    private LocalDateTime dispatchedAt;
    private LocalDateTime receivedAt;
    private List<StockTransferItemDTO> items;
}
