package com.wsid.solution.security;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 비회원 HTTP 세션에 상담 소유자 UUID를 한 번만 발급해요.
 * 같은 세션의 동시 첫 요청이 서로 다른 서버에서 와도 Redis SETNX로 하나의 UUID만 이기게 해요.
 * Redis 키에는 세션 ID 대신 SHA-256 값을 쓰고, UUID·세션 ID는 로그에 남기지 않아요.
 */
@Component
public class AnonymousOwnerIssuer {
    static final String SESSION_ATTRIBUTE = "wsid.solution.anonymousOwnerId";

    private final StringRedisTemplate redis;
    private final String namespace;
    private final Duration ttl;

    public AnonymousOwnerIssuer(StringRedisTemplate redis,
            @Value("${wsid.solution.anonymous-owner-namespace:wsid:solution:anonymous-owner}") String namespace,
            @Value("${spring.session.timeout:30m}") Duration ttl) {
        this.redis = redis;
        this.namespace = namespace;
        this.ttl = ttl;
    }

    public UUID ownerFor(HttpSession session) {
        if (session.getAttribute(SESSION_ATTRIBUTE) instanceof String existing) {
            return UUID.fromString(existing);
        }
        String key = namespace + ":" + sha256(session.getId());
        String candidate = UUID.randomUUID().toString();
        String winner;
        try {
            Boolean created = redis.opsForValue().setIfAbsent(key, candidate, ttl);
            winner = Boolean.TRUE.equals(created) ? candidate : redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            throw new ApiException(ErrorCode.AUTH_SERVICE_UNAVAILABLE);
        }
        if (winner == null) throw new ApiException(ErrorCode.AUTH_SERVICE_UNAVAILABLE);
        session.setAttribute(SESSION_ATTRIBUTE, winner);
        return UUID.fromString(winner);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없어요.", e);
        }
    }
}
