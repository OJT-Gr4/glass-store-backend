package com.glassstore.catalog.service;

import com.glassstore.catalog.dto.SupplierDtos;
import com.glassstore.catalog.entity.Supplier;
import com.glassstore.catalog.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;

    @Transactional(readOnly = true)
    public List<SupplierDtos.Response> getSuppliers() {
        return supplierRepository.findAllByOrderByCodeAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public SupplierDtos.Response create(SupplierDtos.Request request) {
        String code = normalizeCode(request.code());
        if (supplierRepository.existsByCodeIgnoreCase(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã nhà cung cấp đã tồn tại.");
        }
        Supplier supplier = Supplier.builder()
                .code(code)
                .name(normalizeRequired(request.name(), "Tên nhà cung cấp là bắt buộc."))
                .contactPerson(trimToNull(request.contactPerson()))
                .phone(trimToNull(request.phone()))
                .email(normalizeEmail(request.email()))
                .address(trimToNull(request.address()))
                .active(true)
                .build();
        try {
            return toResponse(supplierRepository.saveAndFlush(supplier));
        } catch (DataIntegrityViolationException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã nhà cung cấp đã tồn tại.", duplicate);
        }
    }

    @Transactional
    public SupplierDtos.Response update(Long id, SupplierDtos.Request request) {
        Supplier supplier = findById(id);
        String code = normalizeCode(request.code());
        if (supplierRepository.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã nhà cung cấp đã tồn tại.");
        }
        supplier.setCode(code);
        supplier.setName(normalizeRequired(request.name(), "Tên nhà cung cấp là bắt buộc."));
        supplier.setContactPerson(trimToNull(request.contactPerson()));
        supplier.setPhone(trimToNull(request.phone()));
        supplier.setEmail(normalizeEmail(request.email()));
        supplier.setAddress(trimToNull(request.address()));
        supplier.setUpdatedAt(LocalDateTime.now());
        try {
            return toResponse(supplierRepository.saveAndFlush(supplier));
        } catch (DataIntegrityViolationException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã nhà cung cấp đã tồn tại.", duplicate);
        }
    }

    @Transactional
    public SupplierDtos.Response setActive(Long id, boolean active) {
        Supplier supplier = findById(id);
        supplier.setActive(active);
        supplier.setUpdatedAt(LocalDateTime.now());
        return toResponse(supplier);
    }

    private Supplier findById(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhà cung cấp."));
    }

    private SupplierDtos.Response toResponse(Supplier supplier) {
        return new SupplierDtos.Response(
                supplier.getId(), supplier.getCode(), supplier.getName(), supplier.getContactPerson(),
                supplier.getPhone(), supplier.getEmail(), supplier.getAddress(), supplier.isActive(),
                supplier.getCreatedAt(), supplier.getUpdatedAt());
    }

    private String normalizeCode(String value) {
        return normalizeRequired(value, "Mã nhà cung cấp là bắt buộc.").toUpperCase(Locale.ROOT);
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String normalizeEmail(String value) {
        String email = trimToNull(value);
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
