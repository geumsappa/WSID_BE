package com.wsid.auth.controller;

import com.wsid.auth.dto.AuthRequests;
import com.wsid.auth.dto.AuthResponses;
import com.wsid.auth.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/auth", consumes = MediaType.APPLICATION_JSON_VALUE)
public class RegistrationController {
    private final RegistrationService registration;
    public RegistrationController(RegistrationService registration) { this.registration = registration; }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponses.Success<AuthResponses.Signup> signup(@Valid @RequestBody AuthRequests.Signup request) {
        return AuthResponses.Success.of(registration.signup(request));
    }

    @PostMapping("/email/resend")
    public AuthResponses.Success<AuthResponses.Resend> resend(@Valid @RequestBody AuthRequests.Resend request) {
        return AuthResponses.Success.of(registration.resend(request));
    }

    @PostMapping("/email/verify")
    public AuthResponses.Success<AuthResponses.Verify> verify(@Valid @RequestBody AuthRequests.Verify request) {
        return AuthResponses.Success.of(registration.verify(request));
    }
}
