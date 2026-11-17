package com.wsid.auth.mail;

import com.wsid.auth.config.AuthProperties;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpVerificationMailSender implements VerificationMailSender {
    private static final Logger log = LoggerFactory.getLogger(SmtpVerificationMailSender.class);
    private final JavaMailSender sender;
    private final AuthProperties properties;

    public SmtpVerificationMailSender(JavaMailSender sender, AuthProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    public void requireAvailable() {
        if (!properties.mailEnabled() || properties.mailFrom() == null || properties.mailFrom().isBlank()) {
            log.warn("Verification mail unavailable: mailEnabled={}, fromConfigured={}",
                    properties.mailEnabled(), properties.mailFrom() != null && !properties.mailFrom().isBlank());
            throw new ApiException(ErrorCode.MAIL_SERVICE_UNAVAILABLE);
        }
    }

    public void send(String email, String code, Duration validFor) {
        requireAvailable();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email);
        message.setSubject("[어떡하지] 이메일 인증번호");
        message.setText("인증번호: " + code + "\n유효시간: " + validFor.toSeconds() + "초\n본인이 요청하지 않았다면 무시해 주세요.");
        try {
            sender.send(message);
        } catch (MailException e) {
            // SMTP 예외에는 수신자·원문이 포함될 수 있어 원본 예외를 기록하지 않아요.
            log.warn("Verification SMTP send failed: {}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.MAIL_SERVICE_UNAVAILABLE);
        }
    }
}
