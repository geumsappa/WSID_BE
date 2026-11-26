package com.wsid.auth.repository;

import com.wsid.auth.config.AuthProperties;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class VerificationCodeStore {
    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    // 제한 확인과 카운터 증가를 한 명령으로 처리해 동시 요청도 한도를 넘지 않아요.
    private static final RedisScript<Long> RESERVE_SEND = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            local sent = tonumber(redis.call('GET', KEYS[2]) or '0')
            if sent >= tonumber(ARGV[1]) then return 0 end
            local count = redis.call('INCR', KEYS[2])
            if count == 1 then redis.call('EXPIRE', KEYS[2], ARGV[2]) end
            redis.call('SET', KEYS[1], '1', 'EX', ARGV[3])
            return 1
            """, Long.class);

    private static final RedisScript<Long> ISSUE = RedisScript.of("""
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    private static final RedisScript<String> ATTEMPT = RedisScript.of("""
            local hash = redis.call('GET', KEYS[1])
            if not hash then return 'EXPIRED' end
            local codeCount = tonumber(redis.call('GET', KEYS[2]) or '0')
            local totalCount = tonumber(redis.call('GET', KEYS[3]) or '0')
            if codeCount >= tonumber(ARGV[1]) or totalCount >= tonumber(ARGV[2]) then return 'LIMIT' end
            if redis.call('INCR', KEYS[2]) == 1 then
                redis.call('PEXPIRE', KEYS[2], math.max(redis.call('PTTL', KEYS[1]), 1))
            end
            if redis.call('INCR', KEYS[3]) == 1 then redis.call('EXPIRE', KEYS[3], ARGV[3]) end
            return hash
            """, String.class);

    private static final RedisScript<Long> REMOVE_MATCHING = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                redis.call('DEL', KEYS[1], KEYS[2])
                return 1
            end
            return 0
            """, Long.class);

    public VerificationCodeStore(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void reserveSend(String email) {
        String prefix = prefix(email);
        Long accepted = execute(() -> redis.execute(RESERVE_SEND,
                List.of(prefix + ":cooldown", prefix + ":sends"),
                String.valueOf(properties.maxSends()), String.valueOf(properties.rateWindow().toSeconds()),
                String.valueOf(properties.resendInterval().toSeconds())));
        if (!Long.valueOf(1).equals(accepted)) throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED);
    }

    public void issue(String email, String hash) {
        String prefix = prefix(email);
        execute(() -> redis.execute(ISSUE, List.of(prefix + ":code", prefix + ":attempts"),
                hash, String.valueOf(properties.codeTtl().toSeconds())));
    }

    public String beginAttempt(String email) {
        String prefix = prefix(email);
        String hash = execute(() -> redis.execute(ATTEMPT,
                List.of(prefix + ":code", prefix + ":attempts", prefix + ":total-attempts"),
                String.valueOf(properties.maxAttemptsPerCode()), String.valueOf(properties.maxAttemptsPerWindow()),
                String.valueOf(properties.rateWindow().toSeconds())));
        if (hash == null || "EXPIRED".equals(hash)) throw new ApiException(ErrorCode.VERIFICATION_CODE_EXPIRED);
        if ("LIMIT".equals(hash)) throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED);
        return hash;
    }

    public void removeMatching(String email, String hash) {
        String prefix = prefix(email);
        execute(() -> redis.execute(REMOVE_MATCHING, List.of(prefix + ":code", prefix + ":attempts"), hash));
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            throw new ApiException(ErrorCode.AUTH_SERVICE_UNAVAILABLE);
        }
    }

    private String prefix(String email) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(email.getBytes(StandardCharsets.UTF_8)));
            // 같은 주소의 키는 Redis Cluster에서도 같은 슬롯에 위치해요.
            return properties.redisNamespace() + ":{" + digest + "}";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
