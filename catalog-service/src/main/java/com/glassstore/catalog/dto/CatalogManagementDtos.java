package com.glassstore.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CatalogManagementDtos {

    private CatalogManagementDtos() {}

    public record CategoryRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 100) String slug,
            @Size(max = 255) String description) {}

    public record CategoryResponse(Long id, String name, String slug, String description) {}

    public record BrandRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 50) String country,
            @Size(max = 255) String description) {}

    public record BrandResponse(Long id, String name, String country, String description) {}

    public record LocationCreateRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 100) String area,
            @NotBlank @Pattern(regexp = "BRANCH|WAREHOUSE") String locationType) {}

    public record LocationUpdateRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 100) String area,
            @Pattern(regexp = "BRANCH|WAREHOUSE") String locationType) {}

    public record LocationActiveRequest(Boolean active) {}

    public record LocationResponse(
            Long id, String code, String name, String area, String locationType, boolean active) {}
}
