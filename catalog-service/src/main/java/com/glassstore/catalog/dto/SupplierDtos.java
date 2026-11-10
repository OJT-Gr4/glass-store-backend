package com.glassstore.catalog.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class SupplierDtos {
    private SupplierDtos() {}

    public record Request(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 160) String contactPerson,
            @Size(max = 40) String phone,
            @Email @Size(max = 254) String email,
            @Size(max = 500) String address) {}

    public record ActiveRequest(boolean active) {}

    public record Response(
            Long id,
            String code,
            String name,
            String contactPerson,
            String phone,
            String email,
            String address,
            boolean active,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}
}
