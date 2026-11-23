package com.wsid.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.auth.domain.User;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserAiConsentTest {

    @Test
    void recordsVersionAndKeepsFirstTimeForSameVersion() {
        User user = User.signUp("a@example.com", "hash", Instant.parse("2026-11-01T00:00:00Z"));
        assertNull(user.getAiConsentVersion());

        Instant first = Instant.parse("2026-11-23T01:00:00Z");
        user.agreeAiProcessing("v1", first);
        user.agreeAiProcessing("v1", Instant.parse("2026-11-24T01:00:00Z"));
        assertEquals("v1", user.getAiConsentVersion());
        assertEquals(first, user.getAiConsentAt());

        Instant second = Instant.parse("2026-12-01T01:00:00Z");
        user.agreeAiProcessing("v2", second); // 문구가 바뀌면 새 버전과 시각으로 다시 기록
        assertEquals("v2", user.getAiConsentVersion());
        assertEquals(second, user.getAiConsentAt());
    }
}
