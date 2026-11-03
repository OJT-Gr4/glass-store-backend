package com.glassstore.identity.service.impl;

import com.glassstore.identity.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    @Override
    public void sendOtpEmail(String toEmail, String otpCode) {
        log.info("Preparing OTP email for recipient: {}, OTP Code: {}", toEmail, otpCode);
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(toEmail);
            message.setSubject("[OptiVision - GlassStore] Mã xác thực tài khoản OTP");
            message.setText("Xin chào quý khách,\n\n"
                    + "Mã xác thực OTP của bạn là: " + otpCode + "\n\n"
                    + "Mã có hiệu lực trong vòng 5 phút. Vui lòng không chia sẻ mã này với bất kỳ ai.\n\n"
                    + "Trân trọng,\nĐội ngũ OptiVision GlassStore.");

            mailSender.send(message);
            log.info("Successfully dispatched OTP email to {}", toEmail);
        } catch (Exception e) {
            log.warn("Could not dispatch live email to {} (SMTP may be offline in dev/test): {}. Mock OTP is: {}",
                    toEmail, e.getMessage(), otpCode);
        }
    }
}
