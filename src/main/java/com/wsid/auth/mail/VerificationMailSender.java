package com.wsid.auth.mail;

import java.time.Duration;

public interface VerificationMailSender {
    void requireAvailable();
    void send(String email, String code, Duration validFor);
}
