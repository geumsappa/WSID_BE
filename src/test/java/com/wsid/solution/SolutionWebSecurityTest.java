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
import com.wsid.solution.domain.SolutionMessage;
import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.domain.SolutionSession;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import com.wsid.solution.security.AnonymousOwnerIssuer;
import com.wsid.solution.security.SolutionOwnerResolver;
import com.wsid.solution.security.SolutionSecurityConfig;
import com.wsid.solution.service.SolutionQueryService;
import com.wsid.solution.service.SensitiveDescriptionGuard;
import com.wsid.solution.service.StartSolutionService;
import com.wsid.solution.service.SendSolutionMessageService;
import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

/** 실제 AUTH SecurityConfig + SolutionSecurityConfig 두 체인·CSRF 필터·MVC 바인딩을 거치는 SOL-01/02 웹 테스트. DB·Redis·AI는 대역이에요. */
class SolutionWebSecurityTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "{}", " \n { } \t"})
    void completeAcceptsEmptyBodyOrEmptyObjectAndKeepsSession(String body) throws Exception {
        var browser = new MockHttpSession(); var token = csrf(browser);
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        var beforeMessages = savedMessages.size();
        mvc.perform(completion(browser, token, "301", body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.solutionId").value(301))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.aMapWithSize(2)))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(completion(browser, token, "301", "{}")).andExpect(status().isOk());
        assertEquals(1, stored.getFirst().getStateVersion()); assertEquals(beforeMessages, savedMessages.size());
        assertFalse(browser.isInvalid());
        mvc.perform(get("/api/solutions/sessions/301").session(browser)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.nextQuestion").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(message(browser, token, "301", SendSolutionMessageRequestTest.text(java.util.UUID.randomUUID(), 1, "설명").toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOLUTION_FINISHED"));
        mvc.perform(patch("/api/solutions/sessions/301/facts").session(browser).header(token[0], token[1])
                .contentType(MediaType.APPLICATION_JSON).content("{\"stateVersion\":1,\"changes\":{\"installedApp\":\"NO\"}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOLUTION_FINISHED"));
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        verifyNoInteractions(ai);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "[]", "\"{}\"", " ", "true", "0", "{}{}", "{", "{\"stateVersion\":0}", "{\"x\":1,\"x\":2}"})
    void completeRejectsEveryOtherBodyWithoutCallingDb(String body) throws Exception {
        var browser = new MockHttpSession(); var token = csrf(browser);
        mvc.perform(completion(browser, token, "301", body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(sessions, ai);
    }

    @Test void completionValidationOrderAndOwnershipAreEnforced() throws Exception {
        var browser = new MockHttpSession(); var token = csrf(browser);
        mvc.perform(completion(browser, null, "abc", "{")).andExpect(status().isForbidden());
        mvc.perform(completion(browser, token, "abc", "{")).andExpect(status().isNotFound());
        mvc.perform(completion(browser, token, "301", " ".repeat(16 * 1024 + 1))).andExpect(status().isBadRequest());
        mvc.perform(completion(browser, token, "301", "{}")).andExpect(status().isNotFound());
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        var stranger = new MockHttpSession(); var otherToken = csrf(stranger);
        mvc.perform(completion(stranger, otherToken, "301", "")).andExpect(status().isNotFound());
        mvc.perform(completion(browser, otherToken, "301", "")).andExpect(status().isForbidden());
        var user = mock(User.class); when(user.getId()).thenReturn(42L);
        when(ctx.getBean(LoginService.class).requireActiveUser(42L)).thenReturn(user);
        browser.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(42L, null, List.of())));
        mvc.perform(completion(browser, token, "301", "")).andExpect(status().isNotFound());
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        mvc.perform(completion(browser, null, "302", "")).andExpect(status().isForbidden());
        mvc.perform(completion(browser, token, "302", "")).andExpect(status().isOk());
        when(ctx.getBean(LoginService.class).requireActiveUser(42L)).thenThrow(new ApiException(ErrorCode.LOGIN_REQUIRED));
        mvc.perform(completion(browser, token, "302", "")).andExpect(status().isUnauthorized());
    }

    @Test void completedListRequiresActiveMemberAndIsNotCached() throws Exception {
        var browser = new MockHttpSession();
        mvc.perform(get("/api/solutions/sessions").session(browser))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        var user = mock(User.class); when(user.getId()).thenReturn(42L);
        when(ctx.getBean(LoginService.class).requireActiveUser(42L)).thenReturn(user);
        browser.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(42L, null, List.of())));
        mvc.perform(get("/api/solutions/sessions").session(browser))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/api/solutions/sessions").param("size", "51").session(browser))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test void detailReturnsCreatedAndCompletionTimestamps() throws Exception {
        var browser = new MockHttpSession(); var token = csrf(browser);
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        var active = mvc.perform(get("/api/solutions/sessions/301").session(browser))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var activeData = json.readTree(active).path("data");
        assertTrue(activeData.has("createdAt"));
        assertEquals(stored.getFirst().getCreatedAt(), java.time.Instant.parse(activeData.path("createdAt").asString()));
        assertTrue(activeData.has("completedAt"));
        assertTrue(activeData.path("completedAt").isNull());
        mvc.perform(completion(browser, token, "301", "{}")).andExpect(status().isOk());
        var completed = mvc.perform(get("/api/solutions/sessions/301").session(browser))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var completedData = json.readTree(completed).path("data");
        assertEquals(stored.getFirst().getCreatedAt(), java.time.Instant.parse(completedData.path("createdAt").asString()));
        assertEquals(stored.getFirst().getCompletedAt(), java.time.Instant.parse(completedData.path("completedAt").asString()));
    }

    private MockHttpServletRequestBuilder completion(MockHttpSession session, String[] token, String id, String body) {
        var request = post("/api/solutions/sessions/" + id + "/complete").session(session).content(body);
        return token == null ? request : request.header(token[0], token[1]);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, SolutionSecurityConfig.class, GlobalExceptionHandler.class, LoginController.class,
            SolutionController.class, com.wsid.solution.controller.SolutionListController.class, com.wsid.solution.service.SolutionListService.class, SolutionOwnerResolver.class, AnonymousOwnerIssuer.class,
            SensitiveDescriptionGuard.class, StartSolutionService.class, SolutionQueryService.class, SendSolutionMessageService.class,
            com.wsid.solution.service.UpdateSolutionFactsService.class, com.wsid.solution.service.CompleteSolutionService.class,
            com.wsid.solution.service.SolutionImageService.class})
    static class WebConfiguration {
        @Bean static ConversionService conversionService() { return ApplicationConversionService.getSharedInstance(); }
        @Bean AiClient aiClient() { return mock(AiClient.class); }
        @Bean com.wsid.solution.service.SolutionImageAdviceService imageAdvice() {
            return mock(com.wsid.solution.service.SolutionImageAdviceService.class); // 안내 없음(null)으로 응답
        }
        @Bean LoginService loginService() { return mock(LoginService.class); }
        @Bean com.wsid.auth.CurrentUser currentUser(LoginService login) { return new com.wsid.auth.LoginCurrentUser(login); }
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
    private SolutionMessageRepository messages;
    private final List<SolutionMessage> savedMessages = new ArrayList<>();

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
            ReflectionTestUtils.setField(session, "id", 301L + stored.size());
            stored.add(session);
            return session;
        });
        when(sessions.findOwned(anyLong(), any())).thenAnswer(i -> {
            long id = i.getArgument(0);
            SolutionOwner owner = i.getArgument(1);
            return stored.stream().filter(s -> s.getId() == id && Objects.equals(s.getUserId(), owner.userId())
                    && Objects.equals(s.getAnonymousOwnerId(), owner.anonymousId())).findFirst();
        });
        messages = ctx.getBean(SolutionMessageRepository.class);
        when(messages.save(any())).thenAnswer(i -> { savedMessages.add(i.getArgument(0)); return i.getArgument(0); });
        when(messages.findBySolutionIdOrderBySequenceNoAsc(anyLong())).thenAnswer(i -> savedMessages.stream()
                .filter(m -> m.getSolutionId() == (long) i.getArgument(0)).toList());
        when(messages.lastSequence(anyLong())).thenAnswer(i -> savedMessages.stream()
                .filter(m -> m.getSolutionId() == (long) i.getArgument(0)).mapToInt(SolutionMessage::getSequenceNo).max().orElse(0));
        when(messages.answeredUnknownKeys(anyLong())).thenAnswer(i -> savedMessages.stream()
                .filter(m -> m.getSolutionId() == (long) i.getArgument(0) && "UNKNOWN".equals(m.getSelectedCode()))
                .map(SolutionMessage::getFactKey).filter(Objects::nonNull).toList());
        when(messages.existsBySolutionIdAndClientRequestId(anyLong(), any())).thenAnswer(i -> savedMessages.stream()
                .anyMatch(m -> m.getSolutionId() == (long) i.getArgument(0) && Objects.equals(m.getClientRequestId(), i.getArgument(1))));
        when(sessions.compareAndSet(anyLong(), any(), anyInt(), any())).thenAnswer(i -> {
            var target = stored.stream().filter(s -> s.getId() == (long) i.getArgument(0)).findFirst().orElseThrow();
            SolutionSession.Update update = i.getArgument(3);
            ReflectionTestUtils.setField(target, "factsJson", update.factsJson());
            ReflectionTestUtils.setField(target, "cardCodesJson", update.cardsJson());
            ReflectionTestUtils.setField(target, "nextQuestionJson", update.questionJson());
            ReflectionTestUtils.setField(target, "stateVersion", (int) i.getArgument(2) + 1);
            return 1;
        });
        when(sessions.completeActive(anyLong(), any(), any())).thenAnswer(i -> {
            var target = stored.stream().filter(s -> s.getId() == (long) i.getArgument(0)).findFirst().orElseThrow();
            if (target.getStatus() != com.wsid.solution.domain.SolutionStatus.ACTIVE) return 0;
            ReflectionTestUtils.setField(target, "status", com.wsid.solution.domain.SolutionStatus.COMPLETED);
            ReflectionTestUtils.setField(target, "completedAt", i.getArgument(2));
            ReflectionTestUtils.setField(target, "updatedAt", i.getArgument(2));
            ReflectionTestUtils.setField(target, "nextQuestionJson", null);
            ReflectionTestUtils.setField(target, "stateVersion", target.getStateVersion() + 1);
            return 1;
        });
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(ctx.getBean(StringRedisTemplate.class).opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    /** GET /api/solutions/csrf로 세션과 토큰을 받아요. [헤더 이름, 토큰] */
    private String[] csrf(MockHttpSession session) throws Exception {
        return csrf("/api/solutions/csrf", session);
    }

    private String[] csrf(String path, MockHttpSession session) throws Exception {
        var result = mvc.perform(get(path).session(session)).andExpect(status().isOk())
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
        // 회원은 기존 AUTH 발급 API의 토큰으로도 통과해요(같은 세션 토큰 저장소).
        mvc.perform(start(session, csrf("/api/auth/csrf", session))).andExpect(status().isCreated());
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

    // ---------- SOL-02 ----------

    @Test
    void ownerRestoresSameStateWithoutCsrfAndOthersGet404() throws Exception {
        when(ai.extractSolutionFacts(any())).thenReturn(new SolutionExtractResult(
                SolutionDomainTest.facts("YES", "UNKNOWN", "UNKNOWN", "NO"), List.of("sharedInformation", "installedApp")));
        var session = new MockHttpSession();
        var created = mvc.perform(start(session, csrf(session)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"모르는 사람에게 돈을 보냈어요.\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).path("data").path("solutionId").asLong();

        var restored = mvc.perform(get("/api/solutions/sessions/" + id).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].sequenceNo").value(1))
                .andExpect(jsonPath("$.data.messages[0].sender").value("USER"))
                .andExpect(jsonPath("$.data.messages[0].inputKind").value("TEXT"))
                .andExpect(jsonPath("$.data.messages[0].content").value("모르는 사람에게 돈을 보냈어요."))
                .andExpect(jsonPath("$.data.messages[0].referenceCodes").isEmpty())
                .andExpect(jsonPath("$.data.messages[1].content").value("개인정보를 전달한 적이 있나요?"))
                .andReturn().getResponse().getContentAsString();
        JsonNode before = json.readTree(created).path("data");
        JsonNode after = json.readTree(restored).path("data");
        for (String field : List.of("solutionId", "status", "stateVersion", "facts", "responseCards", "nextQuestion")) {
            assertEquals(before.get(field), after.get(field), field + "는 SOL-01 응답과 같아야 해요.");
        }
        // 설명은 대화 복원을 위해 messages에 보이고, 소유자·요청 ID·카드 버전·LLM 전용 문맥은 내보내지 않아요.
        assertFalse(restored.contains("anonymous") || restored.contains("userId")
                || restored.contains("clientRequestId") || restored.contains("\"version\"")
                || restored.contains("assistContext"));

        var stranger = new MockHttpSession();
        csrf(stranger);
        mvc.perform(get("/api/solutions/sessions/" + id).session(stranger)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOLUTION_NOT_FOUND"));
        mvc.perform(get("/api/solutions/sessions/999").session(session)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOLUTION_NOT_FOUND"));
    }

    @Test
    void withoutSessionRestoreIs404AndCreatesNoSession() throws Exception {
        clearInvocations(ctx.getBean(StringRedisTemplate.class));
        var result = mvc.perform(get("/api/solutions/sessions/301")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOLUTION_NOT_FOUND")).andReturn();
        assertNull(result.getRequest().getSession(false), "조회는 새 세션을 만들지 않아요.");
        verify(sessions, never()).findOwned(anyLong(), any());
        verifyNoInteractions(ctx.getBean(StringRedisTemplate.class));
    }

    @Test
    void malformedIdsAreTheSame404() throws Exception {
        var session = new MockHttpSession();
        csrf(session);
        for (String id : List.of("abc", "0", "-1", "01", "1.5", "99999999999999999999", "9223372036854775808")) {
            mvc.perform(get("/api/solutions/sessions/" + id).session(session)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SOLUTION_NOT_FOUND"));
        }
    }

    @Test
    void afterLoginPreviousAnonymousStartIsNotVisible() throws Exception {
        var session = new MockHttpSession();
        var created = mvc.perform(start(session, csrf(session))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long anonymousId = json.readTree(created).path("data").path("solutionId").asLong();
        mvc.perform(get("/api/solutions/sessions/" + anonymousId).session(session)).andExpect(status().isOk());

        var user = mock(User.class);
        when(user.getId()).thenReturn(42L);
        when(ctx.getBean(LoginService.class).requireActiveUser(42L)).thenReturn(user);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(42L, null, List.of())));
        mvc.perform(get("/api/solutions/sessions/" + anonymousId).session(session)).andExpect(status().isNotFound());
        var member = mvc.perform(start(session, csrf(session))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long memberId = json.readTree(member).path("data").path("solutionId").asLong();
        mvc.perform(get("/api/solutions/sessions/" + memberId).session(session)).andExpect(status().isOk());
    }

    @Test
    void loginOnlyAuthApisStayLoginOnlyAndOtherSolutionPathsStayClosed() throws Exception {
        var session = new MockHttpSession();
        csrf(session);
        // LSH AUTH는 수정하지 않았으므로 /api/auth/csrf는 여전히 회원 전용이에요.
        mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        var token = csrf(session);
        int status = mvc.perform(delete("/api/solutions/sessions/301").session(session).header(token[0], token[1]))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "명세에 없는 DELETE는 공개하지 않아요.");
        int put = mvc.perform(put("/api/solutions/sessions/301").session(session).header(token[0], token[1]))
                .andReturn().getResponse().getStatus();
        assertTrue(put == 401 || put == 403);
        int getMessages = mvc.perform(get("/api/solutions/sessions/301/messages").session(session))
                .andReturn().getResponse().getStatus();
        assertTrue(getMessages == 401 || getMessages == 403);
        // 옛 단수 경로 /api/solution/** 공개 규칙은 LSH SecurityConfig 소유라 그대로 두었어요(연결된 컨트롤러 없음).
    }

    private MockHttpServletRequestBuilder message(MockHttpSession session, String[] token, String id, String body) {
        var request = post("/api/solutions/sessions/" + id + "/messages").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return token == null ? request : request.header(token[0], token[1]);
    }

    // SOL-06 사진 입력
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0};

    private org.springframework.test.web.servlet.RequestBuilder image(MockHttpSession session, String[] token, String id,
            byte[] bytes, String requestId, String stateVersion) {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/solutions/sessions/" + id + "/images")
                .file(new org.springframework.mock.web.MockMultipartFile("images", "내 캡처.png", "image/png", bytes))
                .session(session);
        if (requestId != null) request.param("requestId", requestId);
        if (stateVersion != null) request.param("stateVersion", stateVersion);
        if (token != null) request.header(token[0], token[1]);
        return request;
    }

    @Test void imageTextFlowsLikeTextMessageAndStoresOnlyPlaceholder() throws Exception {
        var browser = new MockHttpSession(); var token = csrf(browser);
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        String recognized = "중고거래 판매자에게 50만원을 송금했어요";
        when(ai.extractText(any())).thenReturn(new com.wsid.ai.dto.OcrExtractResult(recognized, true));
        var facts = SolutionDomainTest.facts("YES", "UNKNOWN", "UNKNOWN", "UNKNOWN");
        when(ai.extractSolutionFacts(any())).thenReturn(new com.wsid.ai.dto.SolutionExtractResult(facts,
                facts.entrySet().stream().filter(e -> e.getValue().equals("UNKNOWN")).map(java.util.Map.Entry::getKey).toList()));
        String requestId = java.util.UUID.randomUUID().toString();
        mvc.perform(image(browser, token, "301", PNG, requestId, "0")).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.stateVersion").value(1))
                .andExpect(jsonPath("$.data.facts.transferredMoney").value("YES"));
        var captor = org.mockito.ArgumentCaptor.forClass(com.wsid.ai.dto.SolutionExtractRequest.class);
        verify(ai).extractSolutionFacts(captor.capture());
        assertEquals("사진 종류: 기타 자료\n인식 문자: " + recognized, captor.getValue().description());
        assertTrue(savedMessages.stream().noneMatch(m -> m.getSafeContent() != null && m.getSafeContent().contains(recognized)));
        assertTrue(savedMessages.stream().anyMatch(m -> "사진(기타 자료)을 제출했습니다.".equals(m.getSafeContent())));
        mvc.perform(image(browser, token, "301", PNG, requestId, "1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
    }

    @Test void imageValidationOrderIsCsrfThenIdThenPartsThenOwnership() throws Exception {
        var browser = new MockHttpSession(); var token = csrf(browser);
        String requestId = java.util.UUID.randomUUID().toString();
        mvc.perform(image(browser, null, "301", PNG, requestId, "0")).andExpect(status().isForbidden());
        mvc.perform(image(browser, token, "abc", PNG, requestId, "0")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOLUTION_NOT_FOUND"));
        mvc.perform(image(browser, token, "301", PNG, null, "0")).andExpect(status().isBadRequest());
        mvc.perform(image(browser, token, "301", PNG, "not-uuid", "0")).andExpect(status().isBadRequest());
        mvc.perform(image(browser, token, "301", PNG, requestId, "-1")).andExpect(status().isBadRequest());
        mvc.perform(image(browser, token, "301", "<html>".getBytes(), requestId, "0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(image(browser, token, "301", PNG, requestId, "0")).andExpect(status().isNotFound());
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        var stranger = new MockHttpSession(); var strangerToken = csrf(stranger);
        mvc.perform(image(stranger, strangerToken, "301", PNG, requestId, "0")).andExpect(status().isNotFound());
        verify(ai, never()).extractText(any());
    }

    @Test void imageOcrFailureAsksForManualInputButSensitiveTextIsAccepted() throws Exception {
        var browser = new MockHttpSession(); var token = csrf(browser);
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        int before = savedMessages.size();
        when(ai.extractText(any())).thenReturn(com.wsid.ai.dto.OcrExtractResult.failed());
        mvc.perform(image(browser, token, "301", PNG, java.util.UUID.randomUUID().toString(), "0"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OCR_FAILED"));
        when(ai.extractText(any())).thenReturn(new com.wsid.ai.dto.OcrExtractResult("", false));
        mvc.perform(image(browser, token, "301", PNG, java.util.UUID.randomUUID().toString(), "0"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OCR_FAILED"));
        verify(ai, never()).extractSolutionFacts(any());
        assertEquals(before, savedMessages.size());
        assertEquals(0, stored.getFirst().getStateVersion());
        // 계좌번호가 찍힌 송금 내역 사진(usable=false)도 거부하지 않고 사실 추출로 이어가요. 원문은 저장하지 않아요.
        when(ai.extractText(any())).thenReturn(new com.wsid.ai.dto.OcrExtractResult("110-123-456789 계좌로 50만원 이체",
                false, com.wsid.ai.dto.OcrImageType.TRANSFER_RECEIPT, "송금 내역입니다."));
        var facts = SolutionDomainTest.facts("YES", "UNKNOWN", "UNKNOWN", "UNKNOWN");
        when(ai.extractSolutionFacts(any())).thenReturn(new com.wsid.ai.dto.SolutionExtractResult(facts,
                facts.entrySet().stream().filter(e -> e.getValue().equals("UNKNOWN")).map(java.util.Map.Entry::getKey).toList()));
        mvc.perform(image(browser, token, "301", PNG, java.util.UUID.randomUUID().toString(), "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.facts.transferredMoney").value("YES"));
        assertTrue(savedMessages.stream().anyMatch(m -> "사진(송금 내역)을 제출했습니다.".equals(m.getSafeContent())));
        assertTrue(savedMessages.stream().noneMatch(m -> m.getSafeContent() != null && m.getSafeContent().contains("110-123")));
    }

    @Test void anonymousChoiceIs200AndRestoresChoiceWithNoRawContent() throws Exception {
        var browser = new MockHttpSession();
        var token = csrf(browser);
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        String body = SendSolutionMessageRequestTest.choice(java.util.UUID.randomUUID(), 0, "transferredMoney", "NO").toString();
        mvc.perform(message(browser, token, "301", body)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.stateVersion").value(1))
                .andExpect(jsonPath("$.data.facts.transferredMoney").value("NO"))
                .andExpect(jsonPath("$.data.nextQuestion.factKey").value("sharedInformation"));
        mvc.perform(get("/api/solutions/sessions/301").session(browser)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[2].inputKind").value("CHOICE"))
                .andExpect(jsonPath("$.data.messages[2].selectedCode").value("NO"));
        mvc.perform(message(browser, token, "301", body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        verifyNoInteractions(ai);
    }

    @Test void messageValidationOrderIsCsrfThenIdThenBodyThenOwnership() throws Exception {
        var browser = new MockHttpSession();
        var token = csrf(browser);
        mvc.perform(message(browser, null, "abc", "{")).andExpect(status().isForbidden());
        mvc.perform(message(browser, token, "abc", "{")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOLUTION_NOT_FOUND"));
        mvc.perform(message(browser, token, "301", "{")).andExpect(status().isBadRequest());
        mvc.perform(message(browser, token, "301", "{} {}")).andExpect(status().isBadRequest());
        mvc.perform(message(browser, token, "301", "{}")).andExpect(status().isBadRequest());
        mvc.perform(message(browser, token, "301", " ".repeat(16 * 1024 + 1))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/solutions/sessions/301/messages").session(browser).header(token[0], token[1]))
                .andExpect(status().isBadRequest());
        mvc.perform(message(browser, token, "301", "{}").contentType(MediaType.TEXT_PLAIN))
                .andExpect(status().isUnsupportedMediaType());
        String valid = SendSolutionMessageRequestTest.choice(java.util.UUID.randomUUID(), 0, "transferredMoney", "NO").toString();
        mvc.perform(message(browser, token, "301", valid)).andExpect(status().isNotFound());
        verifyNoInteractions(ai, sessions);
    }

    @Test void memberAndAnonymousMessagesRequireOwnCookieAndCsrf() throws Exception {
        var browser = new MockHttpSession();
        var token = csrf(browser);
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        String valid = SendSolutionMessageRequestTest.choice(java.util.UUID.randomUUID(), 0, "transferredMoney", "UNKNOWN").toString();
        var stranger = new MockHttpSession(); var strangerToken = csrf(stranger);
        mvc.perform(message(stranger, strangerToken, "301", valid)).andExpect(status().isNotFound());
        mvc.perform(message(browser, strangerToken, "301", valid)).andExpect(status().isForbidden());
        mvc.perform(message(browser, null, "301", valid)).andExpect(status().isForbidden());
        var user = mock(User.class); when(user.getId()).thenReturn(42L);
        when(ctx.getBean(LoginService.class).requireActiveUser(42L)).thenReturn(user);
        browser.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(42L, null, List.of())));
        mvc.perform(message(browser, token, "301", valid)).andExpect(status().isNotFound());
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        mvc.perform(message(browser, null, "302", valid)).andExpect(status().isForbidden());
        mvc.perform(message(browser, token, "302", valid)).andExpect(status().isOk());
        when(ctx.getBean(LoginService.class).requireActiveUser(42L)).thenThrow(new ApiException(ErrorCode.LOGIN_REQUIRED));
        mvc.perform(message(browser, token, "302", valid)).andExpect(status().isUnauthorized());
    }

    @Test void textAiFailureIs503AndDoesNotChangeStoredState() throws Exception {
        var browser = new MockHttpSession(); var token = csrf(browser);
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        when(ai.extractSolutionFacts(any())).thenThrow(new com.wsid.ai.AiUnavailableException("private"));
        String text = SendSolutionMessageRequestTest.text(java.util.UUID.randomUUID(), 0, "비공개 설명").toString();
        mvc.perform(message(browser, token, "301", text)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_SERVICE_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("비공개"))));
        assertEquals(2, savedMessages.size());
        assertEquals(0, stored.getFirst().getStateVersion());
    }

    private MockHttpServletRequestBuilder facts(MockHttpSession session, String[] token, String id, String body) {
        var request = patch("/api/solutions/sessions/" + id + "/facts").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return token == null ? request : request.header(token[0], token[1]);
    }

    @Test void anonymousFactCorrectionReturnsSol03ShapeAndRestoresCorrectionRecord() throws Exception {
        var browser = new MockHttpSession(); var token = csrf(browser);
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        String body = UpdateSolutionFactsRequestTest.body(0, "transferredMoney", "YES", "installedApp", "YES").toString();
        mvc.perform(facts(browser, token, "301", body)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.stateVersion").value(1))
                .andExpect(jsonPath("$.data.facts.installedApp").value("YES"))
                .andExpect(jsonPath("$.data.responseCards.length()").value(2))
                .andExpect(jsonPath("$.data.responseCards[0].code").value("TRANSFER_RESPONSE"))
                .andExpect(jsonPath("$.data.responseCards[1].title").value("악성 앱 대응"))
                .andExpect(jsonPath("$.data.nextQuestion.factKey").value("sharedInformation"));
        mvc.perform(get("/api/solutions/sessions/301").session(browser)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stateVersion").value(1))
                .andExpect(jsonPath("$.data.messages[2].inputKind").value("CHOICE"))
                .andExpect(jsonPath("$.data.messages[2].selectedCode").value("YES"))
                .andExpect(jsonPath("$.data.messages[2].content").value(org.hamcrest.Matchers.startsWith("사실 수정: ")));
        mvc.perform(facts(browser, token, "301", body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_VERSION_CONFLICT"));
        verifyNoInteractions(ai);
    }

    @Test void factValidationOrderIsCsrfThenIdThenBodyThenOwnership() throws Exception {
        var browser = new MockHttpSession(); var token = csrf(browser);
        String valid = UpdateSolutionFactsRequestTest.body(0, "installedApp", "NO").toString();
        mvc.perform(facts(browser, null, "301", valid)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        mvc.perform(facts(browser, token, "abc", "{")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOLUTION_NOT_FOUND"));
        mvc.perform(facts(browser, token, "301", "{")).andExpect(status().isBadRequest());
        mvc.perform(facts(browser, token, "301", valid + " {}")).andExpect(status().isBadRequest());
        mvc.perform(facts(browser, token, "301", "{\"stateVersion\":0,\"changes\":{}}")).andExpect(status().isBadRequest());
        mvc.perform(facts(browser, token, "301", valid).contentType(MediaType.TEXT_PLAIN)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(facts(browser, token, "301", valid)).andExpect(status().isNotFound());
        verifyNoInteractions(sessions);
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        var stranger = new MockHttpSession(); var strangerToken = csrf(stranger);
        mvc.perform(facts(stranger, strangerToken, "301", valid)).andExpect(status().isNotFound());
        mvc.perform(facts(browser, strangerToken, "301", valid)).andExpect(status().isForbidden());
        assertEquals(0, stored.getFirst().getStateVersion());
    }

    @Test void duplicateJsonKeysAreRejectedForFactsAndMessages() throws Exception {
        var browser = new MockHttpSession(); var token = csrf(browser);
        mvc.perform(start(browser, token)).andExpect(status().isCreated());
        mvc.perform(facts(browser, token, "301",
                "{\"stateVersion\":0,\"changes\":{\"installedApp\":\"YES\",\"installedApp\":\"NO\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        String choice = SendSolutionMessageRequestTest.choice(java.util.UUID.randomUUID(), 0, "transferredMoney", "NO").toString();
        mvc.perform(message(browser, token, "301", choice.replace("{", "{\"stateVersion\":5,")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertEquals(0, stored.getFirst().getStateVersion());
        assertEquals(2, savedMessages.size());
    }
}
