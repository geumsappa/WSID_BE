package com.wsid.auth.service;

import com.wsid.auth.domain.User;
import com.wsid.auth.dto.AuthRequests.Login;
import com.wsid.auth.repository.UserRepository;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class LoginService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    public LoginService(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public User authenticate(Login login) {
        if (login.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        User user = users.findByEmail(login.email()).orElse(null);
        boolean hasPassword = user != null && !"!".equals(user.getPasswordHash());
        String hash = hasPassword ? user.getPasswordHash() : dummyHash;
        if (!passwords.matches(login.password(), hash) || !hasPassword) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!user.canLogin()) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        return user;
    }

    public User requireActiveUser(long userId) {
        return users.findById(userId)
                .filter(User::canLogin)
                .orElseThrow(() -> new ApiException(ErrorCode.LOGIN_REQUIRED));
    }

    /** AI 처리(해외 전송) 안내 동의 기록. 같은 버전에 다시 동의하면 처음 시각을 유지해요 */
    @org.springframework.transaction.annotation.Transactional
    public User agreeAiProcessing(long userId, String version) {
        User user = requireActiveUser(userId);
        user.agreeAiProcessing(version, java.time.Instant.now());
        return users.save(user);
    }
}
