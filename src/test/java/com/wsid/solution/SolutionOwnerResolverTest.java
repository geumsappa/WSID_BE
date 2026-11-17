package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wsid.auth.domain.User;
import com.wsid.auth.service.LoginService;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.security.AnonymousOwnerIssuer;
import com.wsid.solution.security.SolutionOwnerResolver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

class SolutionOwnerResolverTest {
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final LoginService logins = mock(LoginService.class);
    private final AnonymousOwnerIssuer issuer = new AnonymousOwnerIssuer(redis, "wsid:test:owner", Duration.ofMinutes(30));
    private final SolutionOwnerResolver resolver = new SolutionOwnerResolver(logins, issuer);

    SolutionOwnerResolverTest() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    private MockHttpServletRequest request(MockHttpSession session) {
        var request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }

    @Test
    void anonymousSessionGetsOneStableUuidAndDifferentSessionsDiffer() {
        var session = new MockHttpSession();
        var first = resolver.resolve(null, request(session));
        var anonymousToken = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        var again = resolver.resolve(anonymousToken, request(session));
        assertFalse(first.isMember());
        assertEquals(first, again);
        verify(values, times(1)).setIfAbsent(anyString(), anyString(), eq(Duration.ofMinutes(30)));
        var other = resolver.resolve(null, request(new MockHttpSession()));
        assertNotEquals(first.anonymousId(), other.anonymousId());
        verifyNoInteractions(logins);
    }

    @Test
    void concurrentFirstRequestAdoptsRedisWinnerAndKeyHidesSessionId() {
        var winner = UUID.randomUUID().toString();
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get(anyString())).thenReturn(winner);
        var session = new MockHttpSession(null, "plain-session-id");
        assertEquals(UUID.fromString(winner), resolver.resolve(null, request(session)).anonymousId());
        verify(values).setIfAbsent(argThat(key -> key.startsWith("wsid:test:owner:") && !key.contains("plain-session-id")),
                anyString(), any(Duration.class));
    }

    @Test
    void redisFailureIsServiceUnavailableNotMemoryFallback() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(new QueryTimeoutException("down"));
        var error = assertThrows(ApiException.class, () -> resolver.resolve(null, request(new MockHttpSession())));
        assertEquals(ErrorCode.AUTH_SERVICE_UNAVAILABLE, error.errorCode());
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get(anyString())).thenReturn(null);
        assertThrows(ApiException.class, () -> resolver.resolve(null, request(new MockHttpSession())));
    }

    @Test
    void loggedInMemberWinsEvenIfAnonymousUuidRemainsInSession() {
        var session = new MockHttpSession();
        resolver.resolve(null, request(session));
        var user = mock(User.class);
        when(user.getId()).thenReturn(42L);
        when(logins.requireActiveUser(42L)).thenReturn(user);
        var member = resolver.resolve(UsernamePasswordAuthenticationToken.authenticated(42L, null, List.of()), request(session));
        assertTrue(member.isMember());
        assertEquals(42L, member.userId());
        assertNull(member.anonymousId());
    }

    @Test
    void inactiveMemberOrForeignPrincipalIsAnErrorNotAnonymous() {
        when(logins.requireActiveUser(9L)).thenThrow(new ApiException(ErrorCode.LOGIN_REQUIRED));
        var inactive = assertThrows(ApiException.class, () -> resolver.resolve(
                UsernamePasswordAuthenticationToken.authenticated(9L, null, List.of()), request(new MockHttpSession())));
        assertEquals(ErrorCode.LOGIN_REQUIRED, inactive.errorCode());
        assertThrows(ApiException.class, () -> resolver.resolve(
                UsernamePasswordAuthenticationToken.authenticated("9", null, List.of()), request(new MockHttpSession())));
        assertThrows(ApiException.class, () -> resolver.resolve(
                UsernamePasswordAuthenticationToken.unauthenticated(9L, null), request(new MockHttpSession())));
        verifyNoInteractions(values);
    }

    // ---------- SOL-02 조회 전용 ----------

    @Test
    void findExistingNeverCreatesSessionOrUuid() {
        var noSession = new MockHttpServletRequest();
        assertTrue(resolver.findExisting(null, noSession).isEmpty());
        assertNull(noSession.getSession(false), "조회는 HTTP 세션을 만들지 않아요.");
        assertTrue(resolver.findExisting(null, request(new MockHttpSession())).isEmpty());
        var forged = new MockHttpSession();
        forged.setAttribute("wsid.solution.anonymousOwnerId", "not-a-uuid");
        assertTrue(resolver.findExisting(null, request(forged)).isEmpty());
        assertEquals("not-a-uuid", forged.getAttribute("wsid.solution.anonymousOwnerId"));
        verifyNoInteractions(values, logins);
    }

    @Test
    void findExistingReusesIssuedAnonymousOwner() {
        var session = new MockHttpSession();
        var issued = resolver.resolve(null, request(session));
        clearInvocations(values);
        assertEquals(issued, resolver.findExisting(null, request(session)).orElseThrow());
        verifyNoInteractions(values);
    }

    @Test
    void findExistingUsesOnlyMemberAfterLoginAndRejectsInactiveMember() {
        var session = new MockHttpSession();
        resolver.resolve(null, request(session));
        var user = mock(User.class);
        when(user.getId()).thenReturn(42L);
        when(logins.requireActiveUser(42L)).thenReturn(user);
        var member = resolver.findExisting(UsernamePasswordAuthenticationToken.authenticated(42L, null, List.of()), request(session));
        assertEquals(SolutionOwner.member(42), member.orElseThrow());
        when(logins.requireActiveUser(9L)).thenThrow(new ApiException(ErrorCode.LOGIN_REQUIRED));
        assertThrows(ApiException.class, () -> resolver.findExisting(
                UsernamePasswordAuthenticationToken.authenticated(9L, null, List.of()), request(session)));
    }
}
