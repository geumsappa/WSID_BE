package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.wsid.ai.AiClient;
import com.wsid.ai.dto.SolutionExtractResult;
import com.wsid.auth.controller.LoginController;
import com.wsid.auth.domain.User;
import com.wsid.auth.service.LoginService;
import com.wsid.common.config.SecurityConfig;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.common.error.GlobalExceptionHandler;
import com.wsid.solution.controller.SolutionController;
import com.wsid.solution.domain.ResponseCardCatalog;
import com.wsid.solution.domain.SolutionSession;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import com.wsid.solution.security.AnonymousOwnerIssuer;
import com.wsid.solution.security.SolutionOwnerResolver;
import com.wsid.solution.service.SensitiveDescriptionGuard;
import com.wsid.solution.service.StartSolutionService;
import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실제 SecurityConfig·CSRF 필터·MVC 바인딩을 거치는 SOL-01 웹 테스트. DB·Redis·AI는 대역이에요. */
class SolutionWebSecurityTest {
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, GlobalExceptionHandler.class, LoginController.class, SolutionController.class,
            SolutionOwnerResolver.class, AnonymousOwnerIssuer.class, SensitiveDescriptionGuard.class,
            StartSolutionService.class})
    static class WebConfiguration {
        @Bean static ConversionService conversionService() { return ApplicationConversionService.getSharedInstance(); }
        @Bean AiClient aiClient() { return mock(AiClient.class); }
        @Bean LoginService loginService() { return mock(LoginService.class); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean SolutionSessionRepository sessions() { return mock(SolutionSessionRepository.class); }
        @Bean SolutionMessageRepository messages() { return mock(SolutionMessageRepository.class); }
        @Bean PlatformTransactionManager transactionManager() { return mock(PlatformTransactionManager.class); }
        @Bean ResponseCardCatalog cards() { return SolutionDomainTest.CATALOG; }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final List<SolutionSession> stored = new ArrayList<>();
    private AnnotationConfigWebApplicationContext ctx;
    private MockMvc mvc;
    private AiClient ai;
    private SolutionSessionRepository sessions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.register(WebConfiguration.class);
        ctx.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(ctx)
                .addFilters(ctx.getBean("springSecurityFilterChain", Filter.class)).build();
        ai = ctx.getBean(AiClient.class);
        sessions = ctx.getBean(SolutionSessionRepository.class);
        when(sessions.saveAndFlush(any())).thenAnswer(i -> {
            SolutionSession session = i.getArgument(0);
            ReflectionTestUtils.setField(session, "id", 301L);
            stored.add(session);
            return session;
        });
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(ctx.getBean(StringRedisTemplate.class).opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    /** GET /api/auth/csrf로 세션과 토큰을 받아요. [헤더 이름, 토큰] */
    private String[] csrf(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
        return new String[] {data.path("headerName").asString(), data.path("token").asString()};
    }

    private MockHttpServletRequestBuilder start(MockHttpSession session, String[] token) {
        var request = post("/api/solutions/sessions").session(session);
        return token == null ? request : request.header(token[0], token[1]);
    }

    @Test
    void anonymousGetsCsrfAndStartsWithoutBodyUsingSameSession() throws Exception {
        var session = new MockHttpSession();
        String[] token = csrf(session);
        assertEquals("X-CSRF-TOKEN", token[0]);
        var body = mvc.perform(start(session, token)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.solutionId").value(301))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.stateVersion").value(0))
                .andExpect(jsonPath("$.data.facts.transferredMoney").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.facts.contactContinues").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.responseCards").isEmpty())
                .andExpect(jsonPath("$.data.nextQuestion.factKey").value("transferredMoney"))
                .andExpect(jsonPath("$.data.nextQuestion.options[2]").value("UNKNOWN"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("anonymous") || body.contains("userId") || body.contains("version\""));
        verifyNoInteractions(ai);
        assertNotNull(stored.getFirst().getAnonymousOwnerId());
        assertNull(stored.getFirst().getUserId());
    }

    @Test
    void describedStartReturnsFactsCardsAndExplicitNullQuestion() throws Exception {
        when(ai.extractSolutionFacts(any())).thenReturn(new SolutionExtractResult(
                SolutionDomainTest.facts("YES", "NO", "NO", "NO"), List.of()));
        var session = new MockHttpSession();
        var body = mvc.perform(start(session, csrf(session)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"모르는 사람에게 돈을 보냈어요.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.facts.transferredMoney").value("YES"))
                .andExpect(jsonPath("$.data.responseCards[0].code").value("TRANSFER_RESPONSE"))
                .andExpect(jsonPath("$.data.responseCards[0].priority").value("URGENT"))
                .andExpect(jsonPath("$.data.responseCards[0].title").value("송금 관련 우선 대응"))
                .andExpect(jsonPath("$.data.responseCards[0].institutionCodes[1]").value("BANK"))
                .andExpect(jsonPath("$.data.responseCards[0].version").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).path("data");
        assertTrue(data.has("nextQuestion"), "모든 사실이 확정되면 nextQuestion을 명시적 null로 보내요.");
        assertTrue(data.get("nextQuestion").isNull());
    }

    @Test
    void missingWrongOrForeignTokenIs403JsonWithoutAiOrStorage() throws Exception {
        var session = new MockHttpSession();
        String[] token = csrf(session);
        mvc.perform(start(session, null)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        mvc.perform(start(session, new String[] {token[0], "forged"})).andExpect(status().isForbidden());
        var other = new MockHttpSession();
        String[] otherToken = csrf(other);
        mvc.perform(start(session, otherToken)).andExpect(status().isForbidden());
        mvc.perform(start(new MockHttpSession(), token)).andExpect(status().isForbidden());
        verifyNoInteractions(ai, sessions);
    }

    @Test
    void memberRequestsAreAlsoCsrfCheckedAndOwnedByMember() throws Exception {
        var user = mock(User.class);
        when(user.getId()).thenReturn(42L);
        when(ctx.getBean(LoginService.class).requireActiveUser(42L)).thenReturn(user);
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(42L, null, List.of())));
        mvc.perform(start(session, null)).andExpect(status().isForbidden());
        mvc.perform(start(session, csrf(session))).andExpect(status().isCreated());
        assertEquals(42L, stored.getFirst().getUserId());
        assertNull(stored.getFirst().getAnonymousOwnerId());
    }

    @Test
    void inactiveMemberIsRejectedNotTreatedAsAnonymous() throws Exception {
        when(ctx.getBean(LoginService.class).requireActiveUser(7L)).thenThrow(new ApiException(ErrorCode.LOGIN_REQUIRED));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(7L, null, List.of())));
        mvc.perform(start(session, csrf(session))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        verifyNoInteractions(sessions);
    }

    @Test
    void bodyFormsAreDistinguished() throws Exception {
        var session = new MockHttpSession();
        String[] token = csrf(session);
        mvc.perform(start(session, token).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isCreated());
        mvc.perform(start(session, token).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isCreated());
        mvc.perform(start(session, token).contentType(MediaType.APPLICATION_JSON).content("{\"description\":"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(start(session, token).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(start(session, token).contentType(MediaType.APPLICATION_JSON).content("{\"description\":12}"))
                .andExpect(status().isBadRequest());
        mvc.perform(start(session, token).contentType(MediaType.TEXT_PLAIN).content("송금했어요"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(start(session, token).content("{\"description\":\"송금했어요\"}"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(start(session, token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"인증번호 123456 알려줬어요\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SENSITIVE_DATA_DETECTED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("123456"))));
        verifyNoInteractions(ai);
        assertEquals(2, stored.size());
    }

    @Test
    void loginOnlyAuthApisStayLoginOnlyAndOtherSolutionPathsStayClosed() throws Exception {
        var session = new MockHttpSession();
        csrf(session);
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        int status = mvc.perform(get("/api/solutions/sessions/301").session(session)).andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "아직 구현하지 않은 SOL-02 경로는 공개하지 않아요.");
        int old = mvc.perform(get("/api/solution/anything").session(session)).andReturn().getResponse().getStatus();
        assertTrue(old == 401 || old == 403, "옛 /api/solution/** 공개 규칙은 제거했어요.");
    }
}
