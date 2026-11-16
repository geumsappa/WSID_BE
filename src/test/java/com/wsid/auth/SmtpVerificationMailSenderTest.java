package com.wsid.auth;

import com.wsid.auth.config.AuthProperties;
import com.wsid.auth.mail.SmtpVerificationMailSender;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmtpVerificationMailSenderTest {
    @Test
    void unconfiguredMailDoesNotContactSmtp() {
        JavaMailSender smtp = mock(JavaMailSender.class);
        var sender = new SmtpVerificationMailSender(smtp, properties(false, ""));
        ApiException error = assertThrows(ApiException.class,
                () -> sender.send("test@example.com", "123456", Duration.ofMinutes(5)));
        assertEquals(ErrorCode.MAIL_SERVICE_UNAVAILABLE, error.errorCode());
        verifyNoInteractions(smtp);
    }

    @Test
    void rendersMailWithoutSendingRealEmail() {
        JavaMailSender smtp = mock(JavaMailSender.class);
        var sender = new SmtpVerificationMailSender(smtp, properties(true, "sender@example.com"));
        sender.send("test@example.com", "123456", Duration.ofMinutes(5));
        var capture = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(smtp).send(capture.capture());
        assertEquals("sender@example.com", capture.getValue().getFrom());
        assertArrayEquals(new String[]{"test@example.com"}, capture.getValue().getTo());
        assertTrue(capture.getValue().getText().contains("123456"));
        assertTrue(capture.getValue().getText().contains("300초"));
    }

    @Test
    void hidesSmtpExceptionDetails() {
        JavaMailSender smtp = mock(JavaMailSender.class);
        doThrow(new MailSendException("sensitive provider detail")).when(smtp).send(any(SimpleMailMessage.class));
        var sender = new SmtpVerificationMailSender(smtp, properties(true, "sender@example.com"));
        ApiException error = assertThrows(ApiException.class,
                () -> sender.send("test@example.com", "123456", Duration.ofMinutes(5)));
        assertEquals(ErrorCode.MAIL_SERVICE_UNAVAILABLE, error.errorCode());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("sensitive"));
    }

    private AuthProperties properties(boolean enabled, String from) {
        return new AuthProperties("test", Duration.ofMinutes(5), Duration.ofSeconds(60),
                Duration.ofHours(1), 5, 5, 20, enabled, from);
    }
}
