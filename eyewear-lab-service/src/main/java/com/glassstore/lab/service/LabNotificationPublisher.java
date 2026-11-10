package com.glassstore.lab.service;

import com.glassstore.lab.client.IdentityNotificationClient;
import com.glassstore.lab.dto.NotificationEventPayload;
import com.glassstore.lab.entity.EyeExamAppointment;
import com.glassstore.lab.entity.LabJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LabNotificationPublisher {

    private final IdentityNotificationClient notificationClient;

    public void publishLabReady(LabJob job, Long customerId) {
        if (job == null || job.getId() == null) {
            return;
        }
        String refCode = firstNonBlank(job.getOrderCode(), job.getTrayNumber(), "LAB-" + job.getId());
        String trayLabel = firstNonBlank(job.getTrayNumber(), "-");
        String customerName = firstNonBlank(job.getCustomerName(), "Khách hàng");

        NotificationEventPayload payload = NotificationEventPayload.builder()
                .eventKey("LAB_READY:JOB:" + job.getId())
                .eventType("LAB_READY")
                .category("LAB_READY")
                .referenceType("LAB_JOB")
                .referenceId(job.getId())
                .referenceCode(refCode)
                .title("Kính sẵn sàng bàn giao (" + refCode + ")")
                .message("Đơn hàng " + refCode + " đã hoàn tất gia công và đạt chuẩn QC.")
                .customerTitle("Kính của bạn đã hoàn tất và sẵn sàng!")
                .customerMessage("Kính thuộc đơn hàng " + refCode + " đã vượt qua kiểm định chất lượng (QC) và sẵn sàng bàn giao/vận chuyển.")
                .staffTitle("Lab #" + job.getId() + " (" + refCode + ") đã đạt QC - Kính sẵn sàng")
                .staffMessage("Khách hàng: " + customerName + " | Khay: " + trayLabel + ". Có thể bàn giao hoặc xuất giao.")
                .actionTab("orders")
                .actionRoute("/orders")
                .sourceService("eyewear-lab-service")
                .customerId(customerId)
                .targetRoles(List.of("ROLE_SALES_STAFF", "ROLE_ADMIN"))
                .build();

        sendSafely(payload);
    }

    public void publishLabRework(LabJob job) {
        if (job == null || job.getId() == null) {
            return;
        }
        String refCode = firstNonBlank(job.getOrderCode(), job.getTrayNumber(), "LAB-" + job.getId());
        String statusTag = job.getStatus() != null ? job.getStatus().name() : "QC_FAILED";
        int rev = job.getReworkCount() != null ? job.getReworkCount() : 0;
        String detail = firstNonBlank(job.getQcNotes(), job.getTechnicianNotes(), "Cần kiểm tra lại thông số gia công.");

        NotificationEventPayload payload = NotificationEventPayload.builder()
                .eventKey("LAB_REWORK:JOB:" + job.getId() + ":" + statusTag + ":REV:" + rev)
                .eventType("LAB_REWORK")
                .category("LAB_REWORK")
                .referenceType("LAB_JOB")
                .referenceId(job.getId())
                .referenceCode(refCode)
                .title("Lab #" + job.getId() + " (" + refCode + ") cần xử lý/mài lại")
                .message(detail)
                .staffTitle("Lab #" + job.getId() + " (" + refCode + ") - " + statusTag)
                .staffMessage("Khách: " + firstNonBlank(job.getCustomerName(), "-") + " | Lý do: " + detail)
                .actionTab("lab")
                .actionRoute("/lab")
                .sourceService("eyewear-lab-service")
                .targetRoles(List.of("ROLE_LAB_TECH", "ROLE_ADMIN"))
                .build();

        sendSafely(payload);
    }

    public void publishAppointmentEvent(EyeExamAppointment appt) {
        if (appt == null || appt.getId() == null || appt.getStatus() == null) {
            return;
        }
        String status = appt.getStatus().trim().toUpperCase();
        String eventType = switch (status) {
            case "WAITING" -> "APPT_CREATED";
            case "IN_EXAM" -> "APPT_IN_EXAM";
            case "COMPLETED" -> "APPT_COMPLETED";
            case "CANCELLED" -> "APPT_CANCELLED";
            default -> "APPT_" + status;
        };

        String ticketCode = firstNonBlank(appt.getTicketCode(), "APPT-" + appt.getId());
        String locationLabel = firstNonBlank(appt.getLocationCode(), "Cửa hàng mặc định");
        String timeLabel = firstNonBlank(appt.getScheduledTime(), "");
        String roomLabel = firstNonBlank(appt.getRoomName(), "Phòng đo mắt");
        String customerName = firstNonBlank(appt.getCustomerName(), "Khách hàng");

        String customerTitle;
        String customerMessage;
        String staffTitle;
        String staffMessage;
        List<String> targetRoles;

        switch (status) {
            case "WAITING" -> {
                customerTitle = "Đặt lịch đo mắt thành công (" + ticketCode + ")";
                customerMessage = "Lịch hẹn tại chi nhánh " + locationLabel + (timeLabel.isBlank() ? "" : " lúc " + timeLabel) + " đang ở hàng chờ.";
                staffTitle = "Lịch hẹn đo mắt mới (" + ticketCode + ")";
                staffMessage = "Khách hàng " + customerName + " đã đặt lịch tại " + locationLabel + (timeLabel.isBlank() ? "." : " lúc " + timeLabel + ".");
                targetRoles = List.of("ROLE_OPTOMETRIST", "ROLE_SALES_STAFF", "ROLE_ADMIN");
            }
            case "IN_EXAM" -> {
                customerTitle = "Đến lượt đo mắt (" + ticketCode + ")";
                customerMessage = "Vui lòng vào " + roomLabel + (appt.getOptometristName() != null ? " gặp KTV " + appt.getOptometristName() : "") + ".";
                staffTitle = "Bắt đầu đo mắt (" + ticketCode + ")";
                staffMessage = customerName + " đang được đo tại " + roomLabel + ".";
                targetRoles = List.of("ROLE_SALES_STAFF", "ROLE_ADMIN");
            }
            case "COMPLETED" -> {
                customerTitle = "Hoàn tất đo mắt (" + ticketCode + ")";
                customerMessage = "Kết quả đo và toa kính của bạn đã được lưu vào hồ sơ thị lực.";
                staffTitle = "Hoàn tất lịch khám (" + ticketCode + ")";
                staffMessage = customerName + " đã hoàn tất đo mắt và lưu toa kính #" + (appt.getPrescriptionId() != null ? appt.getPrescriptionId() : "-") + ".";
                targetRoles = List.of("ROLE_SALES_STAFF", "ROLE_ADMIN");
            }
            case "CANCELLED" -> {
                customerTitle = "Lịch hẹn đo mắt đã hủy (" + ticketCode + ")";
                customerMessage = "Lịch hẹn " + ticketCode + " tại " + locationLabel + " đã được hủy.";
                staffTitle = "Lịch hẹn đo mắt bị hủy (" + ticketCode + ")";
                staffMessage = "Lịch hẹn của " + customerName + " tại " + locationLabel + " đã chuyển sang Đã hủy.";
                targetRoles = List.of("ROLE_OPTOMETRIST", "ROLE_SALES_STAFF", "ROLE_ADMIN");
            }
            default -> {
                customerTitle = "Cập nhật lịch hẹn (" + ticketCode + ")";
                customerMessage = "Trạng thái lịch hẹn hiện tại: " + firstNonBlank(appt.getStatusLabel(), status);
                staffTitle = "Cập nhật lịch hẹn (" + ticketCode + ")";
                staffMessage = customerName + ": " + firstNonBlank(appt.getStatusLabel(), status);
                targetRoles = List.of("ROLE_SALES_STAFF", "ROLE_ADMIN");
            }
        }

        List<Long> explicitUsers = appt.getOptometristId() != null
                ? List.of(appt.getOptometristId())
                : List.of();

        NotificationEventPayload payload = NotificationEventPayload.builder()
                .eventKey("APPT:" + appt.getId() + ":" + status)
                .eventType(eventType)
                .category("APPOINTMENT")
                .referenceType("APPOINTMENT")
                .referenceId(appt.getId())
                .referenceCode(ticketCode)
                .title(customerTitle)
                .message(customerMessage)
                .customerTitle(customerTitle)
                .customerMessage(customerMessage)
                .staffTitle(staffTitle)
                .staffMessage(staffMessage)
                .actionTab("booking")
                .actionRoute("/optometry-appointments")
                .sourceService("eyewear-lab-service")
                .customerId(appt.getCustomerId())
                .explicitUserIds(explicitUsers)
                .targetRoles(targetRoles)
                .build();

        sendSafely(payload);
    }

    private void sendSafely(NotificationEventPayload payload) {
        try {
            notificationClient.publishEvent(payload);
        } catch (Exception ex) {
            log.warn("Không thể phát sự kiện thông báo {} sang identity-service: {}",
                    payload.getEventKey(), ex.getMessage());
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }
}
