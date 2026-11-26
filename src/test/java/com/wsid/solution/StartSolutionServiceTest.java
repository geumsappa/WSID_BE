package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.wsid.ai.AiClient;
import com.wsid.ai.AiUnavailableException;
import com.wsid.ai.dto.SolutionExtractRequest;
import com.wsid.ai.dto.SolutionExtractResult;
import com.wsid.common.domain.InputKind;
import com.wsid.common.domain.MessageSender;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.StartSolutionRequest;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import com.wsid.solution.service.SensitiveDescriptionGuard;
import com.wsid.solution.service.StartSolutionService;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

class StartSolutionServiceTest {
    private final AiClient ai = mock(AiClient.class);
    private final SolutionSessionRepository sessions = mock(SolutionSessionRepository.class);
    private final SolutionMessageRepository messages = mock(SolutionMessageRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AtomicBoolean inTransaction = new AtomicBoolean();
    private final List<SolutionMessage> saved = new ArrayList<>();
    private final AtomicInteger ownerCalls = new AtomicInteger();
    private final Supplier<SolutionOwner> owner = () -> { ownerCalls.incrementAndGet(); return SolutionOwner.anonymous(UUID.randomUUID()); };
    private StartSolutionService service;

    @BeforeEach
    void setUp() throws Exception {
        when(transactions.getTransaction(any())).thenAnswer(i -> { inTransaction.set(true); return mock(TransactionStatus.class); });
        doAnswer(i -> { inTransaction.set(false); return null; }).when(transactions).commit(any());
        doAnswer(i -> { inTransaction.set(false); return null; }).when(transactions).rollback(any());
        when(sessions.saveAndFlush(any())).thenAnswer(i -> {
            assertTrue(inTransaction.get());
            SolutionSession session = i.getArgument(0);
            ReflectionTestUtils.setField(session, "id", 301L);
            return session;
        });
        when(messages.save(any())).thenAnswer(i -> { assertTrue(inTransaction.get()); saved.add(i.getArgument(0)); return i.getArgument(0); });
        Constructor<StartSolutionService> constructor = StartSolutionService.class.getDeclaredConstructor(AiClient.class,
                SensitiveDescriptionGuard.class, ResponseCardCatalog.class, SolutionSessionRepository.class,
                SolutionMessageRepository.class, PlatformTransactionManager.class, Clock.class);
        constructor.setAccessible(true);
        service = constructor.newInstance(ai, new SensitiveDescriptionGuard(), SolutionDomainTest.CATALOG, sessions, messages,
                transactions, Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC));
    }

    private StartSolutionRequest request(String description) {
        var body = new ObjectMapper().createObjectNode().put("description", description);
        return StartSolutionRequest.from(body);
    }

    @Test
    void withoutDescriptionSkipsAiAndAsksFirstQuestion() {
        var response = service.start(StartSolutionRequest.empty(), owner);
        verifyNoInteractions(ai);
        assertEquals(301L, response.solutionId());
        assertEquals(SolutionStatus.ACTIVE, response.status());
        assertEquals(0, response.stateVersion());
        assertTrue(response.facts().values().stream().allMatch("UNKNOWN"::equals));
        assertEquals(List.of(), response.responseCards());
        assertEquals("transferredMoney", response.nextQuestion().factKey());
        assertEquals(2, saved.size());
        assertEquals(MessageSender.SYSTEM, saved.get(0).getSender());
        assertEquals(1, saved.get(0).getSequenceNo());
        assertEquals(2, saved.get(1).getSequenceNo());
        assertEquals(response.nextQuestion().content(), saved.get(1).getSafeContent());
        assertEquals(1, ownerCalls.get());
    }

    @Test
    void appliesAiFactsOutsideTransactionAndReturnsUrgentCard() {
        when(ai.extractSolutionFacts(any())).thenAnswer(i -> {
            assertFalse(inTransaction.get(), "AI-06은 트랜잭션 밖에서 호출해야 해요.");
            SolutionExtractRequest sent = i.getArgument(0);
            assertEquals("모르는 사람에게 돈을 보냈고 연락이 끊겼어요.", sent.description());
            assertTrue(sent.knownFacts().isEmpty());
            assertNotNull(sent.requestId());
            return new SolutionExtractResult(SolutionDomainTest.facts("YES", "UNKNOWN", "UNKNOWN", "NO"),
                    List.of("sharedInformation", "installedApp"), "SECONDHAND_TRADE");
        });
        var response = service.start(request("모르는 사람에게 돈을 보냈고 연락이 끊겼어요."), owner);
        assertEquals("YES", response.facts().get("transferredMoney"));
        assertEquals("NO", response.facts().get("contactContinues"));
        assertEquals(1, response.responseCards().size());
        var card = response.responseCards().getFirst();
        assertEquals("TRANSFER_RESPONSE", card.code());
        assertEquals("URGENT", card.priority());
        assertEquals("송금 관련 우선 대응", card.title());
        assertEquals(List.of("POLICE", "BANK"), card.institutionCodes());
        assertEquals("sharedInformation", response.nextQuestion().factKey());

        var stored = ArgumentCaptor.forClass(SolutionSession.class);
        verify(sessions).saveAndFlush(stored.capture());
        assertFalse(stored.getValue().getFactsJson().contains("모르는"));
        assertEquals("모르는 사람에게 돈을 보냈고 연락이 끊겼어요.", stored.getValue().getTitle());
        assertEquals("SECONDHAND_TRADE", stored.getValue().getFraudTypeCode());
        assertEquals("[{\"code\":\"TRANSFER_RESPONSE\",\"version\":1}]", stored.getValue().getCardCodesJson());
        assertEquals(MessageSender.USER, saved.get(0).getSender());
        assertEquals(InputKind.TEXT, saved.get(0).getInputKind());
        // 설명 원문을 대화 기록에 남겨 새로고침 때 보이고 이후 assist history로 써요.
        assertEquals("모르는 사람에게 돈을 보냈고 연락이 끊겼어요.", saved.get(0).getSafeContent());
        assertNotNull(ReflectionTestUtils.getField(saved.get(0), "clientRequestId"));
        assertNull(ReflectionTestUtils.getField(saved.get(1), "clientRequestId"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"YES,NO,1", "NO,YES,1", "YES,YES,2", "NO,NO,0"})
    void productionYamlCardsAllowYesFactsToStartWithout503(String transferred, String installed, int expected) {
        var guides = new com.wsid.content.GuideCatalog(new ObjectMapper(),
                new org.springframework.core.io.ClassPathResource("content/guides.yml"));
        ReflectionTestUtils.setField(service, "cards", new com.wsid.solution.content.GuideResponseCardCatalog(guides));
        when(ai.extractSolutionFacts(any())).thenReturn(new SolutionExtractResult(
                SolutionDomainTest.facts(transferred, "NO", installed, "NO"), List.of()));
        var response = service.start(request("피해 상황을 확인하고 싶어요"), owner);
        assertEquals(expected, response.responseCards().size());
        verify(sessions).saveAndFlush(any());
        if ("YES".equals(transferred)) assertEquals("TRANSFER_RESPONSE", response.responseCards().getFirst().code());
        if ("YES".equals(installed)) assertEquals("MALICIOUS_APP_RESPONSE", response.responseCards().getLast().code());
    }

    @Test
    void aiFailureStartsWithManualChoiceAnd201() {
        when(ai.extractSolutionFacts(any())).thenThrow(new AiUnavailableException("timeout"));
        var response = service.start(request("[AI_FAIL] 송금했어요"), owner);
        assertTrue(response.facts().values().stream().allMatch("UNKNOWN"::equals));
        assertEquals("transferredMoney", response.nextQuestion().factKey());
        verify(sessions).saveAndFlush(any());
    }

    @Test
    void allKnownFactsKeepActiveWithNullQuestionAndSingleMessage() {
        when(ai.extractSolutionFacts(any())).thenReturn(
                new SolutionExtractResult(SolutionDomainTest.facts("NO", "NO", "NO", "YES"), List.of()));
        var response = service.start(request("설명"), owner);
        assertNull(response.nextQuestion());
        assertEquals(SolutionStatus.ACTIVE, response.status());
        assertEquals(1, saved.size());
    }

    @Test
    void sensitiveDescriptionStopsBeforeOwnerAiAndStorage() {
        var error = assertThrows(ApiException.class, () -> service.start(request("010-1234-5678로 연락이 와요"), owner));
        assertEquals(ErrorCode.SENSITIVE_DATA_DETECTED, error.errorCode());
        assertEquals(0, ownerCalls.get());
        verifyNoInteractions(ai, sessions, messages, transactions);
    }

    @Test
    void missingReviewedCardReturns503WithoutStorage() throws Exception {
        ReflectionTestUtils.setField(service, "cards", (ResponseCardCatalog) code -> Optional.empty());
        when(ai.extractSolutionFacts(any())).thenReturn(
                new SolutionExtractResult(SolutionDomainTest.facts("YES", "NO", "NO", "NO"), List.of()));
        var error = assertThrows(ApiException.class, () -> service.start(request("설명"), owner));
        assertEquals(ErrorCode.RESPONSE_CONTENT_UNAVAILABLE, error.errorCode());
        verifyNoInteractions(sessions, messages);
    }

    @Test
    void ownerErrorsAreNotTurnedIntoAnonymousStarts() {
        Supplier<SolutionOwner> inactive = () -> { throw new ApiException(ErrorCode.LOGIN_REQUIRED); };
        var error = assertThrows(ApiException.class, () -> service.start(StartSolutionRequest.empty(), inactive));
        assertEquals(ErrorCode.LOGIN_REQUIRED, error.errorCode());
        verifyNoInteractions(sessions, messages);
    }

    @Test
    void unexpectedAiErrorsAreNotHiddenAsFallback() {
        when(ai.extractSolutionFacts(any())).thenThrow(new IllegalStateException("bug"));
        assertThrows(IllegalStateException.class, () -> service.start(request("설명"), owner));
        verifyNoInteractions(sessions);
    }

    @Test
    void storageFailureRollsBackAndPropagates() {
        doThrow(new IllegalStateException("db")).when(messages).save(any());
        assertThrows(IllegalStateException.class, () -> service.start(StartSolutionRequest.empty(), owner));
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }
}
