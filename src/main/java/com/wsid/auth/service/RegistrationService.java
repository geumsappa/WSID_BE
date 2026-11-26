package com.wsid.auth.service;

import com.wsid.auth.config.AuthProperties;
import com.wsid.auth.domain.User;
import com.wsid.auth.domain.UserStatus;
import com.wsid.auth.dto.AuthRequests;
import com.wsid.auth.dto.AuthResponses;
import com.wsid.auth.mail.VerificationMailSender;
import com.wsid.auth.repository.UserRepository;
import com.wsid.auth.repository.VerificationCodeStore;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RegistrationService {
    private final UserRepository users;
    private final VerificationCodeStore codes;
    private final VerificationMailSender mail;
    private final PasswordEncoder encoder;
    private final AuthProperties properties;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    public RegistrationService(UserRepository users, VerificationCodeStore codes,
            VerificationMailSender mail, PasswordEncoder encoder, AuthProperties properties,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.codes = codes;
        this.mail = mail;
        this.encoder = encoder;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public AuthResponses.Signup signup(AuthRequests.Signup request) {
        // BCrypt 제한은 문자 수가 아니라 UTF-8 바이트 수예요. 비밀번호는 trim하지 않아요.
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        if (users.existsByEmail(request.email())) throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        mail.requireAvailable();
        codes.reserveSend(request.email());
        String passwordHash = encoder.encode(request.password());
        User user;
        try {
            user = transaction.execute(tx -> users.saveAndFlush(
                    User.signUp(request.email(), passwordHash, request.gender(), Instant.now())));
        } catch (DataIntegrityViolationException e) {
            if (users.existsByEmail(request.email())) throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
            throw e;
        }
        // 회원은 먼저 커밋해요. SMTP 실패 시 PENDING 회원은 재발송으로 복구할 수 있어요.
        sendCode(request.email());
        return new AuthResponses.Signup(user.getId(), user.getEmail(), user.getStatus(), true);
    }

    public AuthResponses.Resend resend(AuthRequests.Resend request) {
        mail.requireAvailable();
        // 미가입·인증 완료 주소에도 같은 제한과 성공 안내를 적용해요.
        codes.reserveSend(request.email());
        sendCode(request.email());
        return new AuthResponses.Resend("인증 메일 발송 요청이 처리되었습니다.");
    }

    private void sendCode(String email) {
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String hash = encoder.encode(code);
        Boolean issued = transaction.execute(tx -> {
            User user = users.findByEmailForUpdate(email).orElse(null);
            if (user == null || user.getStatus() != UserStatus.PENDING) return false;
            codes.issue(email, hash);
            return true;
        });
        if (!Boolean.TRUE.equals(issued)) return;
        try {
            // SMTP 호출 중에는 DB 트랜잭션과 행 잠금을 유지하지 않아요.
            mail.send(email, code, properties.codeTtl());
        } catch (ApiException e) {
            removeBestEffort(email, hash);
            throw e;
        }
    }

    public AuthResponses.Verify verify(AuthRequests.Verify request) {
        String hash = transaction.execute(tx -> {
            User user = users.findByEmailForUpdate(request.email())
                    .orElseThrow(() -> new ApiException(ErrorCode.VERIFICATION_CODE_EXPIRED));
            if (user.getStatus() != UserStatus.PENDING) {
                throw new ApiException(ErrorCode.VERIFICATION_CODE_EXPIRED);
            }
            String stored = codes.beginAttempt(request.email());
            if (!encoder.matches(request.code(), stored)) throw new ApiException(ErrorCode.INVALID_VERIFICATION_CODE);
            user.verifyEmail(Instant.now());
            users.saveAndFlush(user);
            return stored;
        });
        // DB 커밋 뒤 코드를 지워요. DB 실패 시 유효 코드를 잃지 않아 재시도할 수 있어요.
        // Redis 삭제 실패라도 ACTIVE 회원 검사가 코드 재사용을 막고 TTL이 잔여 키를 정리해요.
        removeBestEffort(request.email(), hash);
        return new AuthResponses.Verify(true, UserStatus.ACTIVE);
    }

    private void removeBestEffort(String email, String hash) {
        try {
            codes.removeMatching(email, hash);
        } catch (ApiException e) {
            if (e.errorCode() != ErrorCode.AUTH_SERVICE_UNAVAILABLE) throw e;
        }
    }
}
