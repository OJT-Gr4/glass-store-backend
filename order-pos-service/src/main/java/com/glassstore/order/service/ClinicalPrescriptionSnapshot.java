package com.glassstore.order.service;

import com.glassstore.order.entity.OrderPrescription;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Copies server-verified measurements once, so dispatch/retry never follows a newer revision. */
final class ClinicalPrescriptionSnapshot {
    private ClinicalPrescriptionSnapshot() {}

    static OrderPrescription from(Map<String, Object> data, Long id, Long customerId) {
        if (data == null || !Objects.equals(Objects.toString(data.get("id"), ""), Objects.toString(id, ""))
                || customerId == null || !customerId.toString().equals(Objects.toString(data.get("customerId"), "")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Toa kính không thuộc khách hàng của đơn.");
        try {
            return OrderPrescription.builder().version(data.get("version") == null ? 0L : new BigDecimal(data.get("version").toString()).longValueExact())
                    .odSph(decimal(data, "odSph")).odCyl(decimal(data, "odCyl"))
                    .odAxis(decimal(data, "odAxis").intValueExact()).odAdd(decimal(data, "odAdd"))
                    .osSph(decimal(data, "osSph")).osCyl(decimal(data, "osCyl"))
                    .osAxis(decimal(data, "osAxis").intValueExact()).osAdd(decimal(data, "osAdd"))
                    .pd(decimal(data, "pd")).source("CLINICAL_REVISION").build();
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Toa kính thiếu hoặc sai thông số bắt buộc.");
        }
    }

    private static BigDecimal decimal(Map<String, Object> data, String field) {
        return new BigDecimal(Objects.requireNonNull(data.get(field), field).toString());
    }

    static Map<String, Object> toMap(OrderPrescription rx, Long id, Long customerId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id); data.put("customerId", customerId); data.put("version", rx.getVersion());
        data.put("odSph", rx.getOdSph()); data.put("odCyl", rx.getOdCyl()); data.put("odAxis", rx.getOdAxis()); data.put("odAdd", rx.getOdAdd());
        data.put("osSph", rx.getOsSph()); data.put("osCyl", rx.getOsCyl()); data.put("osAxis", rx.getOsAxis()); data.put("osAdd", rx.getOsAdd());
        data.put("pd", rx.getPd());
        return data;
    }
}
