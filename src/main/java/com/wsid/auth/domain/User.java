package com.wsid.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** users 테이블. 이메일 인증을 마쳐야 ACTIVE가 돼요 (AUTH-001, AUTH-004). */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 320)
    private String email;

    /** 비밀번호 원문은 저장하지 않고 해시만 저장해요. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(length = 10)
    private String gender;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
        // JPA용
    }

    /** AUTH-001: 가입 신청. 이메일 인증 전이라 PENDING이에요. */
    public static User signUp(String email, String passwordHash, Instant now) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.status = UserStatus.PENDING;
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    /** 로컬 개발용 계정. 비밀번호 해시 자리에 해시가 아닌 값을 넣어 로그인할 수 없게 해요. */
    public static User localDev(String email, Instant now) {
        User user = signUp(email, "!", now);
        user.verifyEmail(now);
        return user;
    }

    /** AUTH-004: 인증 코드 확인이 끝나면 계정을 활성화해요. */
    public void verifyEmail(Instant now) {
        this.status = UserStatus.ACTIVE;
        this.emailVerifiedAt = now;
        this.updatedAt = now;
    }

    public boolean canLogin() {
        return status == UserStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserStatus getStatus() {
        return status;
    }
}
