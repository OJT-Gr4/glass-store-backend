package com.glassstore.identity.controller;

import com.glassstore.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class InternalPortalController {

    /**
     * Bán Hàng POS: Chỉ dành cho SALES_STAFF và ADMIN
     */
    @GetMapping("/pos/status")
    @PreAuthorize("hasAnyRole('SALES_STAFF', 'ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPosStatus() {
        return ResponseEntity.ok(ApiResponse.success("Truy cập hệ thống POS bán hàng quầy thành công", Map.of(
                "module", "POS_TERMINAL",
                "status", "ONLINE",
                "counter", "POS-01")));
    }

    /**
     * Phòng Đo Mắt Khúc Xạ: Chỉ dành cho OPTOMETRIST và ADMIN
     */
    @GetMapping("/optometry/exams")
    @PreAuthorize("hasAnyRole('OPTOMETRIST', 'ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOptometryExams() {
        return ResponseEntity.ok(ApiResponse.success("Truy cập phòng đo mắt khúc xạ thành công", Map.of(
                "module", "OPTOMETRY_CLINIC",
                "standards", "12-STEP-WCO",
                "queueLength", 3)));
    }

    /**
     * Xưởng Gia Công Lab & QC: Chỉ dành cho LAB_TECH và ADMIN
     */
    @GetMapping("/lab/queue")
    @PreAuthorize("hasAnyRole('LAB_TECH', 'ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLabQueue() {
        return ResponseEntity.ok(ApiResponse.success("Truy cập xưởng gia công mài lắp Lab thành công", Map.of(
                "module", "LAB_EDGING_QC",
                "equipment", "Essilor Delta 200",
                "processingOrders", 5)));
    }

    /**
     * Quản Trị Kho và Doanh Thu: Chỉ dành riêng cho ADMIN
     * Nhân viên SALES_STAFF, OPTOMETRIST, LAB_TECH sẽ nhận HTTP 403 Forbidden
     */
    @GetMapping("/inventory-revenue/report")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getInventoryAndRevenueReport() {
        return ResponseEntity.ok(ApiResponse.success("Báo cáo doanh thu & quản trị tồn kho chi nhánh", Map.of(
                "module", "ADMIN_FINANCE_INVENTORY",
                "monthlyRevenue", 1250000000L,
                "inventoryTotalSKUs", 450)));
    }
}
