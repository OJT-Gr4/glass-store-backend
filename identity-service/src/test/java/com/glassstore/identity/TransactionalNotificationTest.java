package com.glassstore.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.common.security.ServiceTokenIssuer;
import com.glassstore.identity.dto.NotificationEventRequest;
import com.glassstore.identity.entity.Role;
import com.glassstore.identity.entity.User;
import com.glassstore.identity.repository.RoleRepository;
import com.glassstore.identity.repository.TransactionNotificationRepository;
import com.glassstore.identity.repository.UserRepository;
import com.glassstore.identity.security.JwtTokenProvider;
import com.glassstore.identity.service.NotificationChannelDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class TransactionalNotificationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private TransactionNotificationRepository notificationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Value("${jwt.secret:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}")
    private String jwtSecret;

    private User customerA;
    private User customerB;
    private User staffUser;
    private User adminUser;

    private String customerAToken;
    private String customerBToken;
    private String staffToken;
    private String adminToken;
    private String serviceToken;

    @BeforeEach
    void setUp() {
        Role adminRole = ensureRole("ROLE_ADMIN");
        Role staffRole = ensureRole("ROLE_SALES_STAFF");
        Role customerRole = ensureRole("ROLE_CUSTOMER");

        customerA = ensureUser("cus_notif_a", "cus_notif_a@example.com", "0901111111", "Khách Hàng A", customerRole);
        customerB = ensureUser("cus_notif_b", "cus_notif_b@example.com", "0902222222", "Khách Hàng B", customerRole);
        staffUser = ensureUser("staff_notif", "staff_notif@optivision.vn", "0903333333", "Nhân Viên POS", staffRole);
        adminUser = ensureUser("admin_notif", "admin_notif@optivision.vn", "0904444444", "Quản Trị Viên", adminRole);

        notificationRepository.deleteAll(notificationRepository.findByRecipientUserIdOrderByCreatedAtDescIdDesc(customerA.getId()));
        notificationRepository.deleteAll(notificationRepository.findByRecipientUserIdOrderByCreatedAtDescIdDesc(customerB.getId()));
        notificationRepository.deleteAll(notificationRepository.findByRecipientUserIdOrderByCreatedAtDescIdDesc(staffUser.getId()));
        notificationRepository.deleteAll(notificationRepository.findByRecipientUserIdOrderByCreatedAtDescIdDesc(adminUser.getId()));

        customerAToken = jwtTokenProvider.generateAccessToken(customerA);
        customerBToken = jwtTokenProvider.generateAccessToken(customerB);
        staffToken = jwtTokenProvider.generateAccessToken(staffUser);
        adminToken = jwtTokenProvider.generateAccessToken(adminUser);
        serviceToken = ServiceTokenIssuer.issue(jwtSecret, "eyewear-lab-service");
    }

    private Role ensureRole(String roleName) {
        return roleRepository.findByName(roleName)
                .orElseGet(() -> roleRepository.save(Role.builder().name(roleName).description(roleName).build()));
    }

    private User ensureUser(String username, String email, String phone, String fullName, Role role) {
        return userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(User.builder()
                        .username(username)
                        .email(email)
                        .phoneNumber(phone)
                        .fullName(fullName)
                        .passwordHash(passwordEncoder.encode("Password123!"))
                        .status("ACTIVE")
                        .roles(new HashSet<>(Collections.singletonList(role)))
                        .build()));
    }

    @Test
    @DisplayName("1. Phát sự kiện kính sẵn sàng tạo 1 thông báo mỗi người nhận, retry cùng eventKey không nhân đôi, không giả mạo SMS")
    void publishLabReady_idempotentRetryAndBlockedSmsStatus() throws Exception {
        String eventKey = "LAB_READY:JOB:" + System.nanoTime();
        NotificationEventRequest payload = NotificationEventRequest.builder()
                .eventKey(eventKey)
                .eventType("LAB_READY")
                .category("LAB_READY")
                .referenceType("LAB_JOB")
                .referenceId(88L)
                .referenceCode("ORD-8801")
                .customerTitle("Kính của bạn đã hoàn tất và sẵn sàng!")
                .customerMessage("Đơn hàng ORD-8801 đã đạt QC.")
                .staffTitle("Lab #88 đạt QC - Sẵn sàng")
                .staffMessage("Đơn ORD-8801 sẵn sàng bàn giao.")
                .actionTab("orders")
                .actionRoute("/orders")
                .sourceService("eyewear-lab-service")
                .customerId(customerA.getId())
                .targetRoles(List.of("ROLE_SALES_STAFF", "ROLE_ADMIN"))
                .build();
        String json = objectMapper.writeValueAsString(payload);

        // Lần 1: Tạo thông báo thật
        mockMvc.perform(post("/internal/notifications/events")
                        .header("Authorization", "Bearer " + serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // Lần 2 (Retry cùng eventKey): Chống trùng theo dedupKey, không tạo bản sao
        mockMvc.perform(post("/internal/notifications/events")
                        .header("Authorization", "Bearer " + serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        // Kiểm tra Customer A: Đúng 1 thông báo, inApp DELIVERED, sms BLOCKED_PROVIDER_UNCONFIGURED
        MvcResult cusARes = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + customerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerUserId").value(customerA.getId()))
                .andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].inAppStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.data.items[0].smsStatus").value("BLOCKED_PROVIDER_UNCONFIGURED"))
                .andExpect(jsonPath("$.data.items[0].title").value("Kính của bạn đã hoàn tất và sẵn sàng!"))
                .andReturn();

        // Kiểm tra Customer B (người khác): 0 thông báo
        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + customerBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        // Kiểm tra Staff nhận đúng 1 thông báo staffTitle (không nhân đôi sau retry)
        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].inAppStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.data.items[0].smsStatus").value("SKIPPED"))
                .andExpect(jsonPath("$.data.items[0].title").value("Lab #88 đạt QC - Sẵn sàng"));

        // Kiểm tra Customer token không được gọi internal endpoint -> 403 Forbidden
        mockMvc.perform(post("/internal/notifications/events")
                        .header("Authorization", "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());

        JsonNode cusAJson = objectMapper.readTree(cusARes.getResponse().getContentAsString());
        assertThat(cusAJson.path("data").path("items").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("2. Chủ sở hữu đọc & đánh dấu đã đọc tồn tại qua re-fetch; người dùng khác bị 403 Forbidden")
    void ownershipEnforcementAndMarkReadPersistence() throws Exception {
        String eventKey = "APPT:" + System.nanoTime() + ":IN_EXAM";
        Map<String, Object> payload = Map.of(
                "eventKey", eventKey,
                "eventType", "APPT_IN_EXAM",
                "category", "APPOINTMENT",
                "referenceType", "APPOINTMENT",
                "referenceId", 99L,
                "referenceCode", "EP-TEST-99",
                "customerTitle", "Đến lượt đo mắt (EP-TEST-99)",
                "customerMessage", "Vui lòng vào Phòng đo 01",
                "customerId", customerA.getId()
        );

        MvcResult postRes = mockMvc.perform(post("/internal/notifications/events")
                        .header("Authorization", "Bearer " + serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode createdArray = objectMapper.readTree(postRes.getResponse().getContentAsString()).path("data");
        long notifId = createdArray.get(0).path("id").asLong();

        // Người khác (Customer B) xem inbox của Customer A -> 403
        mockMvc.perform(get("/api/notifications")
                        .param("recipientUserId", String.valueOf(customerA.getId()))
                        .header("Authorization", "Bearer " + customerBToken))
                .andExpect(status().isForbidden());

        // Nhân viên (Staff) cố mạo danh xem inbox của Customer A -> 403
        mockMvc.perform(get("/api/notifications")
                        .param("recipientUserId", String.valueOf(customerA.getId()))
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());

        // Người khác (Customer B) cố đánh dấu đã đọc thông báo của Customer A -> 403
        mockMvc.perform(patch("/api/notifications/" + notifId + "/read")
                        .header("Authorization", "Bearer " + customerBToken))
                .andExpect(status().isForbidden());

        // Chưa đọc = 1
        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + customerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(1));

        // Chủ sở hữu (Customer A) đánh dấu đã đọc -> 200 & read = true
        mockMvc.perform(patch("/api/notifications/" + notifId + "/read")
                        .header("Authorization", "Bearer " + customerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true))
                .andExpect(jsonPath("$.data.readAt").isNotEmpty());

        // Reload / re-fetch inbox -> vẫn read = true và unreadCount = 0
        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + customerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(0))
                .andExpect(jsonPath("$.data.items[0].read").value(true));
    }

    @Test
    @DisplayName("3. Cấu hình kênh thông báo (FR-ADM-10): Admin cập nhật được, Khách hàng/Staff bị 403 Forbidden")
    void channelConfigRbacAndPersistence() throws Exception {
        String body = "{\"smsEnabled\":false,\"emailEnabled\":false,\"pollIntervalSeconds\":20}";

        mockMvc.perform(put("/api/notification-channels")
                        .header("Authorization", "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/notification-channels")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/notification-channels")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.smsEnabled").value(false))
                .andExpect(jsonPath("$.data.pollIntervalSeconds").value(20))
                .andExpect(jsonPath("$.data.smsProviderConfigured").value(false))
                .andExpect(jsonPath("$.data.smsProviderStatus").value("BLOCKED_PROVIDER_UNCONFIGURED"));

        // Khôi phục mặc định smsEnabled=true, pollIntervalSeconds=15 cho các bài test tiếp theo
        mockMvc.perform(put("/api/notification-channels")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"smsEnabled\":true,\"emailEnabled\":false,\"pollIntervalSeconds\":15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.smsEnabled").value(true))
                .andExpect(jsonPath("$.data.pollIntervalSeconds").value(15));
    }

    @Test
    @DisplayName("4. SMS/Email Dispatcher: Thiếu provider ghi BLOCKED; 2xx ghi SENT; 5xx/Exception ghi FAILED")
    @SuppressWarnings("unchecked")
    void channelDispatcherHonestOutcomes() throws Exception {
        NotificationChannelDispatcher unconfigured = new NotificationChannelDispatcher(
                objectMapper, mock(HttpClient.class), "", "", "OptiVision", "sandbox-fake", "", "no-reply@optivision.vn");
        assertThat(unconfigured.dispatchSms(true, true, "0901234567", "ORD-1", "T", "M").status())
                .isEqualTo(NotificationChannelDispatcher.STATUS_BLOCKED_UNCONFIGURED);
        assertThat(unconfigured.dispatchEmail(true, true, "a@b.vn", "ORD-1", "T", "M").status())
                .isEqualTo(NotificationChannelDispatcher.STATUS_BLOCKED_UNCONFIGURED);

        HttpClient okClient = mock(HttpClient.class);
        HttpResponse<String> okResp = mock(HttpResponse.class);
        when(okResp.statusCode()).thenReturn(202);
        when(okClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(okResp);
        NotificationChannelDispatcher okDispatcher = new NotificationChannelDispatcher(
                objectMapper, okClient, "https://sms.example.com/send", "key", "OptiVision",
                "https://mail.example.com/send", "key2", "no-reply@optivision.vn");
        assertThat(okDispatcher.dispatchSms(true, true, "0901234567", "ORD-1", "T", "M").status())
                .isEqualTo(NotificationChannelDispatcher.STATUS_SENT);
        assertThat(okDispatcher.dispatchEmail(true, true, "a@b.vn", "ORD-1", "T", "M").status())
                .isEqualTo(NotificationChannelDispatcher.STATUS_SENT);

        HttpClient failClient = mock(HttpClient.class);
        when(failClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));
        NotificationChannelDispatcher failDispatcher = new NotificationChannelDispatcher(
                objectMapper, failClient, "https://sms.example.com/send", "key", "OptiVision",
                "", "", "no-reply@optivision.vn");
        assertThat(failDispatcher.dispatchSms(true, true, "0901234567", "ORD-1", "T", "M").status())
                .isEqualTo(NotificationChannelDispatcher.STATUS_FAILED);
    }
}
