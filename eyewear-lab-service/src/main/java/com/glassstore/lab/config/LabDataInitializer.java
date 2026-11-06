package com.glassstore.lab.config;

import com.glassstore.lab.entity.EyeExamAppointment;
import com.glassstore.lab.entity.Prescription;
import com.glassstore.lab.entity.Warranty;
import com.glassstore.lab.repository.EyeExamAppointmentRepository;
import com.glassstore.lab.repository.PrescriptionRepository;
import com.glassstore.lab.repository.WarrantyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "glassstore.demo.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class LabDataInitializer implements CommandLineRunner {

    private final PrescriptionRepository prescriptionRepository;
    private final EyeExamAppointmentRepository appointmentRepository;
    private final WarrantyRepository warrantyRepository;

    @Override
    public void run(String... args) {
        log.info("Khởi tạo và đồng bộ dữ liệu mẫu cho Eyewear Lab & Khúc Xạ...");

        // 1. Seed Lịch hẹn khám mắt mẫu nếu chưa có
        if (appointmentRepository.count() == 0) {
            EyeExamAppointment app1 = EyeExamAppointment.builder()
                    .ticketCode("EP-1001")
                    .ticketType("E-Pass Online")
                    .customerId(10L)
                    .customerName("Nguyễn Hoàng Duy")
                    .customerPhone("0918732998")
                    .customerTier("VIP Silver")
                    .optometristId(6L)
                    .optometristName("BS. Lê Thị Ánh Tuyết")
                    .roomName("Phòng Khúc Xạ 01")
                    .roomDevice("Nidek RT-6100")
                    .status("IN_EXAM")
                    .statusLabel("Đang đo")
                    .priorityLevel(1)
                    .waitMinutes(5)
                    .examStep("Bước 04/12 - Đo khúc xạ khách quan (Auto Ref)")
                    .examStepIndex(4)
                    .scheduledTime("09:30")
                    .notes("Khám định kỳ, mỏi mắt khi làm việc máy tính")
                    .createdAt(LocalDateTime.now().minusMinutes(25))
                    .build();

            EyeExamAppointment app2 = EyeExamAppointment.builder()
                    .ticketCode("EP-1002")
                    .ticketType("Kiosk Bấm Số")
                    .customerId(11L)
                    .customerName("Lê Thúy Kiều")
                    .customerPhone("0988124551")
                    .customerTier("VIP Gold")
                    .optometristId(6L)
                    .optometristName("BS. Lê Thị Ánh Tuyết")
                    .roomName("Phòng Khúc Xạ 01")
                    .roomDevice("Nidek RT-6100")
                    .status("WAITING")
                    .statusLabel("Đang chờ")
                    .priorityLevel(1)
                    .waitMinutes(12)
                    .examStep("Chờ gọi vào phòng đo")
                    .examStepIndex(1)
                    .scheduledTime("10:00")
                    .notes("Kiểm tra độ tăng cận và loạn thị")
                    .createdAt(LocalDateTime.now().minusMinutes(15))
                    .build();

            EyeExamAppointment app3 = EyeExamAppointment.builder()
                    .ticketCode("EP-1003")
                    .ticketType("Kiosk Bấm Số")
                    .customerId(12L)
                    .customerName("Trịnh Minh Quang")
                    .customerPhone("0912449002")
                    .customerTier("Thành Viên")
                    .optometristId(7L)
                    .optometristName("BS. Lâm Hoàng Phúc")
                    .roomName("Phòng Khúc Xạ 02")
                    .roomDevice("Huvitz HDR-9000")
                    .status("WAITING")
                    .statusLabel("Đang chờ")
                    .priorityLevel(2)
                    .waitMinutes(18)
                    .examStep("Chờ gọi vào phòng đo")
                    .examStepIndex(1)
                    .scheduledTime("10:15")
                    .notes("Đo cắt kính cận nặng")
                    .createdAt(LocalDateTime.now().minusMinutes(10))
                    .build();

            EyeExamAppointment app4 = EyeExamAppointment.builder()
                    .ticketCode("EP-1004")
                    .ticketType("E-Pass Online")
                    .customerId(13L)
                    .customerName("Phạm Thị Bích Ngọc")
                    .customerPhone("0977898332")
                    .customerTier("Thành Viên")
                    .optometristId(6L)
                    .optometristName("BS. Lê Thị Ánh Tuyết")
                    .roomName("Phòng Khúc Xạ 01")
                    .status("COMPLETED")
                    .statusLabel("Đã xong")
                    .priorityLevel(2)
                    .waitMinutes(0)
                    .examStep("Đã ký duyệt đơn kính & chuyển POS")
                    .examStepIndex(12)
                    .scheduledTime("09:00")
                    .notes("Đã đo xong và ký toa kính lão thị đa tròng")
                    .createdAt(LocalDateTime.now().minusHours(1))
                    .build();

            appointmentRepository.saveAll(List.of(app1, app2, app3, app4));
            log.info("Nạp seed data 4 lượt khám mắt thành công!");
        }

        // 2. Seed Toa kính mẫu nếu chưa có
        if (prescriptionRepository.count() == 0) {
            Prescription rx1 = Prescription.builder()
                    .customerId(10L)
                    .customerName("Nguyễn Hoàng Duy")
                    .optometristId(6L)
                    .optometristName("BS. Lê Thị Ánh Tuyết")
                    .odSph(new BigDecimal("-2.25"))
                    .odCyl(new BigDecimal("-0.75"))
                    .odAxis(175)
                    .odAdd(BigDecimal.ZERO)
                    .osSph(new BigDecimal("-2.00"))
                    .osCyl(new BigDecimal("-0.50"))
                    .osAxis(180)
                    .osAdd(BigDecimal.ZERO)
                    .pd(new BigDecimal("63.5"))
                    .notes("Cận loạn khúc xạ học đường & văn phòng. Khuyên dùng tròng lọc ánh sáng xanh 1.60.")
                    .createdAt(LocalDateTime.now().minusDays(1))
                    .build();

            Prescription rx2 = Prescription.builder()
                    .customerId(13L)
                    .customerName("Phạm Thị Bích Ngọc")
                    .optometristId(6L)
                    .optometristName("BS. Lê Thị Ánh Tuyết")
                    .odSph(new BigDecimal("1.50"))
                    .odCyl(BigDecimal.ZERO)
                    .odAxis(0)
                    .odAdd(new BigDecimal("2.00"))
                    .osSph(new BigDecimal("1.75"))
                    .osCyl(BigDecimal.ZERO)
                    .osAxis(0)
                    .osAdd(new BigDecimal("2.00"))
                    .pd(new BigDecimal("64.0"))
                    .notes("Lão thị nhìn gần mờ. Chỉ định kính đa tròng kỹ thuật số Varilux Comfort Max.")
                    .createdAt(LocalDateTime.now().minusHours(1))
                    .build();

            prescriptionRepository.saveAll(List.of(rx1, rx2));
            log.info("Nạp seed data 2 toa kính mẫu thành công!");
        }

        // 3. Seed Thẻ bảo hành mẫu nếu chưa có
        if (warrantyRepository.count() == 0) {
            log.info("Nạp seed data bảo hành mẫu cho db_lab...");

            // 3.1 Thẻ bảo hành Gọng kính Titanium Slimline
            Warranty war1 = Warranty.builder()
                    .warrantyCode("WAR-OPTI-9842")
                    .originalOrderCode("ORD-2026-9842")
                    .customerId(10L)
                    .customerName("Nguyễn Hoàng Duy")
                    .customerPhone("0918732998")
                    .customerTier("VIP Diamond")
                    .productName("Titanium Slimline Gold Edition")
                    .productSku("FRM-BOLON-BLK")
                    .productDetails("Hợp kim Titanium siêu nhẹ phủ mạ PVD Vàng Champagne cao cấp")
                    .issueDescription("Kính bị lỏng ốc càng bên phải, xin nắn lại form ôm sát sống mũi")
                    .claimType("FRAME_TECH")
                    .policyRef("BR-35 (Bảo hành kết cấu 12 tháng)")
                    .techNote("Kỹ thuật viên đã nắn chỉnh và siết ốc ren chống trượt.")
                    .cost("0 đ (Miễn phí)")
                    .techName("Nguyễn Văn Khoa (Lab Q.1)")
                    .status("PROCESSING_COUNTER")
                    .statusLabel("Đang thẩm định")
                    .statusColor("#0284c7")
                    .timeLabel("Hiệu lực 365 ngày (đến 15/10/2027)")
                    .createdAt(LocalDateTime.now().minusDays(15))
                    .updatedAt(LocalDateTime.now())
                    .build();

            // 3.2 Thẻ bảo hành Tròng kính Crizal Prevencia 1.67
            Warranty war2 = Warranty.builder()
                    .warrantyCode("WAR-CRI-5510")
                    .originalOrderCode("ORD-2026-9842")
                    .customerId(10L)
                    .customerName("Nguyễn Hoàng Duy")
                    .customerPhone("0918732998")
                    .customerTier("VIP Diamond")
                    .productName("Crizal Prevencia 1.67 Aspheric")
                    .productSku("LNS-CHEMI-160")
                    .productDetails("Lọc ánh sáng xanh chọn lọc • Chống phản quang UV400 • Chống trầy xước chuẩn Pháp")
                    .issueDescription("Mờ mắt sau 30 ngày sử dụng, kiểm tra lại tâm quang học")
                    .claimType("RX_INSURANCE")
                    .policyRef("Bảo hiểm đổi số độ khúc xạ 6 tháng")
                    .techNote("Bác sĩ khúc xạ đã kiểm tra lại số độ OD/OS.")
                    .cost("0 đ (Miễn phí)")
                    .techName("BS. Lê Thị Ánh Tuyết")
                    .status("LAB_APPROVED")
                    .statusLabel("Đã duyệt xưởng")
                    .statusColor("#10b981")
                    .timeLabel("Hiệu lực 310 ngày (đến 15/04/2027)")
                    .createdAt(LocalDateTime.now().minusDays(30))
                    .updatedAt(LocalDateTime.now())
                    .build();

            warrantyRepository.saveAll(List.of(war1, war2));
            log.info("Nạp xong 2 thẻ bảo hành mẫu điện tử chuẩn xác.");
        }
    }
}
