package com.glassstore.identity.service.impl;

import com.glassstore.identity.service.EmailOtpSender;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmtpEmailOtpSender implements EmailOtpSender {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:ptran4109@gmail.com}")
    private String mailFrom;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");

    @Override
    public void sendOtpEmail(String recipientEmail, String recipientName, String otpCode, String purpose, LocalDateTime expiresAt) {
        if (recipientEmail == null || recipientEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Địa chỉ email nhận mã OTP không hợp lệ");
        }

        String targetName = (recipientName != null && !recipientName.isBlank()) ? recipientName.trim() : "Quý khách";
        String purposeDisplay = "PASSWORD_RESET".equalsIgnoreCase(purpose)
                ? "Đặt lại mật khẩu đăng nhập"
                : "Xác thực tài khoản người dùng";
        String formattedExpiry = expiresAt != null ? expiresAt.format(TIME_FORMATTER) : "5 phút";

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            helper.setFrom(mailFrom, "OptiVision Eyewear");
            helper.setTo(recipientEmail.trim());
            helper.setSubject("[OptiVision] Mã xác thực OTP của bạn: " + otpCode);

            String html = """
                <!DOCTYPE html>
                <html lang="vi">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Mã OTP OptiVision</title>
                </head>
                <body style="margin: 0; padding: 0; background-color: #f4f6fa; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1e293b;">
                  <div style="max-width: 560px; margin: 30px auto; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 16px rgba(0,0,0,0.06); border: 1px solid #e2e8f0;">
                    
                    <!-- Header -->
                    <div style="background: linear-gradient(135deg, #086288 0%%, #004967 100%%); padding: 28px 32px; text-align: center; color: #ffffff;">
                      <h1 style="margin: 0; font-size: 24px; font-weight: 800; letter-spacing: -0.5px;">👓 OptiVision Eyewear</h1>
                      <p style="margin: 6px 0 0 0; font-size: 13px; opacity: 0.9; color: #e0f2fe;">Hệ Thống Bán Lẻ Kính Mắt &amp; Chăm Sóc Thị Lực Toàn Diện</p>
                    </div>

                    <!-- Body -->
                    <div style="padding: 32px 32px 24px 32px;">
                      <p style="font-size: 15px; margin: 0 0 16px 0; line-height: 1.6;">
                        Xin chào <strong>%s</strong>,
                      </p>
                      <p style="font-size: 14px; margin: 0 0 20px 0; line-height: 1.6; color: #475569;">
                        Bạn vừa yêu cầu mã xác thực OTP cho mục đích: <strong>%s</strong> trên hệ thống OptiVision.
                      </p>

                      <!-- OTP Box -->
                      <div style="background: #f0f9ff; border: 2px dashed #0284c7; border-radius: 12px; padding: 20px; text-align: center; margin: 24px 0;">
                        <span style="display: block; font-size: 12px; text-transform: uppercase; color: #0284c7; font-weight: 700; letter-spacing: 1px; margin-bottom: 6px;">Mã xác thực OTP (6 chữ số)</span>
                        <div style="font-size: 36px; font-weight: 800; letter-spacing: 8px; color: #086288; font-family: monospace;">%s</div>
                        <span style="display: block; font-size: 12px; color: #64748b; margin-top: 8px;">Hiệu lực đến: <strong>%s</strong> (khoảng 5 phút)</span>
                      </div>

                      <!-- Warning -->
                      <div style="background-color: #fffbeb; border-left: 4px solid #f59e0b; padding: 12px 16px; border-radius: 6px; margin: 20px 0;">
                        <p style="margin: 0; font-size: 12px; color: #92400e; line-height: 1.5;">
                          ⚠️ <strong>Lưu ý bảo mật:</strong> Không chia sẻ mã OTP này cho bất kỳ ai, kể cả nhân viên OptiVision. Nếu bạn không thực hiện yêu cầu này, vui lòng bỏ qua email hoặc đổi mật khẩu ngay để bảo đảm an toàn.
                        </p>
                      </div>

                      <p style="font-size: 13px; margin: 24px 0 0 0; color: #64748b; line-height: 1.5;">
                        Trân trọng cảm ơn,<br>
                        <strong style="color: #086288;">Đội ngũ OptiVision Việt Nam</strong>
                      </p>
                    </div>

                    <!-- Footer -->
                    <div style="background-color: #f8fafc; padding: 16px 32px; text-align: center; border-top: 1px solid #e2e8f0; font-size: 11px; color: #94a3b8;">
                      Hệ thống tự động gửi từ OptiVision. Vui lòng không trả lời trực tiếp email này.<br>
                      Hotline hỗ trợ kỹ thuật: 1900 6868 | Website: https://ojtgroup4.japaneast.cloudapp.azure.com
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(targetName, purposeDisplay, otpCode, formattedExpiry);

            helper.setText(html, true);
            mailSender.send(message);
            log.info("📧 [EMAIL OTP SENT] Đã gửi mã OTP qua Gmail SMTP tới email [{}] (User: {}, Purpose: {})",
                    recipientEmail, targetName, purpose);
        } catch (Exception e) {
            log.error("❌ [EMAIL OTP ERROR] Gửi email OTP thất bại tới [{}]: {}", recipientEmail, e.getMessage(), e);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể gửi mã OTP qua email lúc này. Vui lòng thử lại sau ít phút hoặc liên hệ quản trị viên."
            );
        }
    }
}
