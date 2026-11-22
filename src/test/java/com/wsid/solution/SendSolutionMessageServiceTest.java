package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wsid.ai.AiClient;
import com.wsid.ai.AiUnavailableException;
import com.wsid.ai.dto.SolutionExtractResult;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.SendSolutionMessageRequest;
import com.wsid.solution.repository.*;
import com.wsid.solution.service.SendSolutionMessageService;
import com.wsid.solution.service.SensitiveDescriptionGuard;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;

class SendSolutionMessageServiceTest {
    private final AiClient ai = mock(AiClient.class);
    private final SolutionSessionRepository sessions = mock(SolutionSessionRepository.class);
    private final SolutionMessageRepository messages = mock(SolutionMessageRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final ResponseCardCatalog cards = mock(ResponseCardCatalog.class);
    private final SolutionOwner owner = SolutionOwner.anonymous(UUID.randomUUID());
    private final AtomicBoolean active = new AtomicBoolean();
    private final List<SolutionMessage> saved = new ArrayList<>();
    private SolutionSession session;
    private SendSolutionMessageService service;

    @BeforeEach void setup() {
        service = new SendSolutionMessageService(ai, new SensitiveDescriptionGuard(), cards, sessions, messages, manager);
        when(manager.getTransaction(any())).thenAnswer(i -> {
            TransactionDefinition def = i.getArgument(0);
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, def.getIsolationLevel());
            active.set(true); return mock(TransactionStatus.class);
        });
        doAnswer(i -> { active.set(false); return null; }).when(manager).commit(any());
        doAnswer(i -> { active.set(false); return null; }).when(manager).rollback(any());
        initial(SolutionFacts.unknown());
        when(sessions.findOwned(301, owner)).thenAnswer(i -> Optional.of(session));
        when(messages.answeredUnknownKeys(301)).thenReturn(List.of());
        when(messages.lastSequence(301)).thenReturn(2);
        when(messages.save(any())).thenAnswer(i -> { assertTrue(active.get()); saved.add(i.getArgument(0)); return i.getArgument(0); });
        when(sessions.compareAndSet(eq(301L), eq(owner), eq(2), any())).thenAnswer(i -> {
            assertTrue(active.get());
            SolutionSession.Update u = i.getArgument(3);
            ReflectionTestUtils.setField(session, "factsJson", u.factsJson());
            ReflectionTestUtils.setField(session, "cardCodesJson", u.cardsJson());
            ReflectionTestUtils.setField(session, "nextQuestionJson", u.questionJson());
            ReflectionTestUtils.setField(session, "stateVersion", 3);
            return 1;
        });
        when(cards.findReviewed(anyString())).thenAnswer(i -> SolutionDomainTest.CATALOG.findReviewed(i.getArgument(0)));
    }

    private void initial(SolutionFacts facts) {
        session = SolutionSession.start(owner, facts, List.of(), SolutionQuestion.next(facts).orElse(null), java.time.Instant.now());
        ReflectionTestUtils.setField(session, "id", 301L);
        ReflectionTestUtils.setField(session, "stateVersion", 2);
    }
    private SendSolutionMessageRequest choice(String key, String value) {
        return SendSolutionMessageRequest.from(SendSolutionMessageRequestTest.choice(UUID.randomUUID(), 2, key, value));
    }
    private SendSolutionMessageRequest text(String content) {
        return SendSolutionMessageRequest.from(SendSolutionMessageRequestTest.text(UUID.randomUUID(), 2, content));
    }
    private com.wsid.solution.dto.SolutionMessageResponse send(SendSolutionMessageRequest input) {
        return service.send(301, input, () -> Optional.of(owner));
    }
    private SolutionExtractResult result(String a, String b, String c, String d) {
        var facts = SolutionDomainTest.facts(a,b,c,d);
        return new SolutionExtractResult(facts, facts.entrySet().stream().filter(e -> e.getValue().equals("UNKNOWN")).map(Map.Entry::getKey).toList());
    }
    private void fails(ErrorCode code, Runnable run) { assertEquals(code, assertThrows(ApiException.class, run::run).errorCode()); }

    @Test void choiceSkipsAiAndPersistsSelectedFactAndNextQuestion() {
        var response = send(choice("transferredMoney", "NO"));
        assertEquals(3, response.stateVersion());
        assertEquals("NO", response.facts().get("transferredMoney"));
        assertEquals("sharedInformation", response.nextQuestion().factKey());
        assertEquals(2, saved.size());
        assertEquals("transferredMoney", saved.getFirst().getFactKey());
        assertNotNull(saved.getFirst().getClientRequestId());
        assertNull(saved.getLast().getClientRequestId());
        verifyNoInteractions(ai);
    }

    @Test void unknownChoiceAndPriorSkippedQuestionsAdvanceWithoutLoop() {
        when(messages.answeredUnknownKeys(301)).thenReturn(List.of("sharedInformation"));
        var response = send(choice("transferredMoney", "UNKNOWN"));
        assertEquals("UNKNOWN", response.facts().get("transferredMoney"));
        assertEquals("installedApp", response.nextQuestion().factKey());
    }

    @Test void textFillsUnknownAndKeepsKnownFactsOutsideTransaction() {
        initial(SolutionFacts.unknown().withChoice(SolutionFact.CONTACT_CONTINUES, FactValue.NO));
        when(ai.extractSolutionFacts(any())).thenAnswer(i -> {
            assertFalse(active.get());
            com.wsid.ai.dto.SolutionExtractRequest req = i.getArgument(0);
            assertEquals(Map.of("contactContinues", "NO"), req.knownFacts());
            return result("YES", "UNKNOWN", "UNKNOWN", "YES");
        });
        var request = text("비공개 새 설명");
        var response = send(request);
        assertEquals("NO", response.facts().get("contactContinues"));
        assertEquals("YES", response.facts().get("transferredMoney"));
        assertEquals(1, response.responseCards().size());
        assertEquals("sharedInformation", response.nextQuestion().factKey());
        assertTrue(saved.stream().noneMatch(m -> m.getSafeContent().contains(request.content())));
        var captured = ArgumentCaptor.forClass(com.wsid.ai.dto.SolutionExtractRequest.class);
        verify(ai).extractSolutionFacts(captured.capture());
        assertEquals(request.requestId(), captured.getValue().requestId());
    }

    @Test void unchangedQuestionDoesNotAccumulateSystemMessagesButVersionIncreases() {
        when(ai.extractSolutionFacts(any())).thenReturn(result("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN"));
        var response = send(text("변화를 확인할 수 없는 설명"));
        assertEquals(3, response.stateVersion());
        assertEquals("transferredMoney", response.nextQuestion().factKey());
        assertEquals(1, saved.size());
    }

    @Test void answeredAllReturnsNullAndStaysActive() {
        initial(SolutionFacts.fromStrings(SolutionDomainTest.facts("NO", "NO", "NO", "UNKNOWN")));
        var response = send(choice("contactContinues", "UNKNOWN"));
        assertNull(response.nextQuestion());
        assertEquals(SolutionStatus.ACTIVE, session.getStatus());
        assertEquals(1, saved.size());
    }

    @Test void rejectsMissingOwnerAndForeignSessionWithoutAi() {
        fails(ErrorCode.SOLUTION_NOT_FOUND, () -> service.send(301, text("설명"), Optional::empty));
        when(sessions.findOwned(301, owner)).thenReturn(Optional.empty());
        fails(ErrorCode.SOLUTION_NOT_FOUND, () -> send(text("설명")));
        verifyNoInteractions(ai); assertTrue(saved.isEmpty());
    }

    @Test void rejectsDuplicateBeforeStaleVersionWithoutAi() {
        var request = text("설명");
        when(messages.existsBySolutionIdAndClientRequestId(301L, request.requestId())).thenReturn(true);
        ReflectionTestUtils.setField(session, "stateVersion", 3);
        fails(ErrorCode.DUPLICATE_REQUEST, () -> send(request));
        verifyNoInteractions(ai);
    }

    @Test void rejectsStaleVersionWrongQuestionNoQuestionAndCompletedSession() {
        ReflectionTestUtils.setField(session, "stateVersion", 3);
        fails(ErrorCode.STATE_VERSION_CONFLICT, () -> send(choice("installedApp", "NO")));
        ReflectionTestUtils.setField(session, "stateVersion", 2);
        fails(ErrorCode.INVALID_REQUEST, () -> send(choice("installedApp", "NO")));
        ReflectionTestUtils.setField(session, "nextQuestionJson", null);
        fails(ErrorCode.INVALID_REQUEST, () -> send(choice("transferredMoney", "NO")));
        ReflectionTestUtils.setField(session, "status", SolutionStatus.COMPLETED);
        fails(ErrorCode.SOLUTION_FINISHED, () -> send(text("설명")));
        verifyNoInteractions(ai);
    }

    @Test void sensitiveInputAndAiFailureDoNotSaveAnything() {
        fails(ErrorCode.SENSITIVE_DATA_DETECTED, () -> send(text("연락처 010-1234-5678")));
        verifyNoInteractions(ai);
        when(ai.extractSolutionFacts(any())).thenThrow(new AiUnavailableException("절대 로그에 남기지 않을 원문"));
        fails(ErrorCode.AI_SERVICE_UNAVAILABLE, () -> send(text("안전한 설명")));
        verify(sessions, never()).compareAndSet(anyLong(), any(), anyInt(), any());
        assertTrue(saved.isEmpty());
    }

    @Test void missingCardRollsBackAndDoesNotUpdateFacts() {
        when(cards.findReviewed(any())).thenReturn(Optional.empty());
        fails(ErrorCode.RESPONSE_CONTENT_UNAVAILABLE, () -> send(choice("transferredMoney", "YES")));
        verify(sessions, never()).compareAndSet(anyLong(), any(), anyInt(), any());
        verify(manager).rollback(any());
        assertEquals(2, session.getStateVersion());
    }

    @Test void rechecksVersionAndOwnerAfterAi() {
        when(ai.extractSolutionFacts(any())).thenAnswer(i -> {
            ReflectionTestUtils.setField(session, "stateVersion", 3);
            return result("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN");
        });
        fails(ErrorCode.STATE_VERSION_CONFLICT, () -> send(text("설명")));
        initial(SolutionFacts.unknown());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Supplier<Optional<SolutionOwner>> expired = () -> calls.incrementAndGet() == 1 ? Optional.of(owner) : Optional.empty();
        fails(ErrorCode.SOLUTION_NOT_FOUND, () -> service.send(301, text("설명"), expired));
        assertTrue(saved.isEmpty());
    }

    @Test void casLoserUsesFreshTransactionAndDistinguishesDuplicate() {
        var request = choice("transferredMoney", "NO");
        when(sessions.compareAndSet(anyLong(), any(), anyInt(), any())).thenAnswer(i -> {
            when(messages.existsBySolutionIdAndClientRequestId(301L, request.requestId())).thenReturn(true);
            return 0;
        });
        fails(ErrorCode.DUPLICATE_REQUEST, () -> send(request));
        verify(manager).rollback(any());
        assertTrue(saved.isEmpty());
    }

    @Test void unrelatedConstraintFailureIsNotHiddenAsConflict() {
        var failure = new DataIntegrityViolationException("unrelated");
        doThrow(failure).when(messages).flush();
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> send(choice("transferredMoney", "NO"))));
        verify(manager).rollback(any());
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void conflictsLogOnlyCountAndRequestIdNotRawTextOrFactKeys(CapturedOutput output) {
        initial(SolutionFacts.unknown().withChoice(SolutionFact.CONTACT_CONTINUES, FactValue.NO));
        when(ai.extractSolutionFacts(any())).thenReturn(result("UNKNOWN", "UNKNOWN", "UNKNOWN", "YES"));
        var request = text("절대로로그에남기지않을충돌설명");
        send(request);
        assertTrue(output.getAll().contains("conflictCount=1"));
        assertTrue(output.getAll().contains(request.requestId().toString()));
        assertFalse(output.getAll().contains(request.content()));
        assertFalse(output.getAll().contains("contactContinues"));
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void aiFailureNeverLogsCauseContainingPrivateText(CapturedOutput output) {
        String cause = "PRIVATE_AI_FAILURE_CAUSE_MUST_NOT_BE_LOGGED";
        when(ai.extractSolutionFacts(any())).thenThrow(new AiUnavailableException(cause));
        var request = text("비공개상담원문");
        fails(ErrorCode.AI_SERVICE_UNAVAILABLE, () -> send(request));
        assertFalse(output.getAll().contains(cause));
        assertFalse(output.getAll().contains(request.content()));
    }

    // SOL-06: 사진에서 읽은 글자를 SOL-03 텍스트처럼 반영
    private com.wsid.solution.dto.SolutionMessageResponse sendImage(UUID requestId, Supplier<String> recognize) {
        return service.sendRecognizedText(301, requestId, 2, photo(recognize), () -> Optional.of(owner));
    }

    private static Supplier<SendSolutionMessageService.RecognizedPhoto> photo(Supplier<String> recognize) {
        return () -> new SendSolutionMessageService.RecognizedPhoto(recognize.get(), "사진(송금 내역)을 제출했습니다.");
    }

    @Test void recognizedTextRunsOcrOutsideTransactionAndSavesOnlyPlaceholder() {
        String recognized = "중고거래 판매자에게 50만원을 송금했어요";
        when(ai.extractSolutionFacts(any())).thenAnswer(i -> {
            assertFalse(active.get());
            com.wsid.ai.dto.SolutionExtractRequest req = i.getArgument(0);
            assertEquals(recognized, req.description());
            return result("YES", "UNKNOWN", "UNKNOWN", "UNKNOWN");
        });
        var response = sendImage(UUID.randomUUID(), () -> { assertFalse(active.get()); return recognized; });
        assertEquals(3, response.stateVersion());
        assertEquals("YES", response.facts().get("transferredMoney"));
        assertEquals("사진(송금 내역)을 제출했습니다.", saved.getFirst().getSafeContent());
        assertTrue(saved.stream().noneMatch(m -> recognized.equals(m.getSafeContent())));
    }

    @Test void recognizedTextChecksSessionBeforeCallingOcr() {
        var ocrCalled = new AtomicBoolean();
        Supplier<String> ocr = () -> { ocrCalled.set(true); return "설명"; };
        UUID duplicate = UUID.randomUUID();
        when(messages.existsBySolutionIdAndClientRequestId(301L, duplicate)).thenReturn(true);
        fails(ErrorCode.DUPLICATE_REQUEST, () -> sendImage(duplicate, ocr));
        ReflectionTestUtils.setField(session, "stateVersion", 3);
        fails(ErrorCode.STATE_VERSION_CONFLICT, () -> sendImage(UUID.randomUUID(), ocr));
        fails(ErrorCode.SOLUTION_NOT_FOUND,
                () -> service.sendRecognizedText(301, UUID.randomUUID(), 2, photo(ocr), Optional::empty));
        assertFalse(ocrCalled.get());
        verifyNoInteractions(ai);
    }

    @Test void photoSkipsSensitiveGuardButChatTextKeepsIt() {
        String withAccount = "계좌 110-123-456789로 50만원 보냈어요";
        // 일반 채팅(SOL-03 TEXT)은 계속 민감정보 확인으로 거부돼요.
        var chat = SendSolutionMessageRequest.from(new tools.jackson.databind.ObjectMapper().readTree(
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"stateVersion\":2,\"inputKind\":\"TEXT\",\"content\":\""
                        + withAccount + "\"}"));
        fails(ErrorCode.SENSITIVE_DATA_DETECTED, () -> service.send(301, chat, () -> Optional.of(owner)));
        verifyNoInteractions(ai);
        // 사진은 계좌·전화번호가 찍히는 게 보통이라 확인을 건너뛰고 사실 추출로 넘어가요. 원문은 저장하지 않아요.
        when(ai.extractSolutionFacts(any())).thenReturn(result("YES", "UNKNOWN", "UNKNOWN", "UNKNOWN"));
        var response = sendImage(UUID.randomUUID(), () -> withAccount);
        assertEquals("YES", response.facts().get("transferredMoney"));
        assertTrue(saved.stream().noneMatch(m -> m.getSafeContent() != null && m.getSafeContent().contains("110-123")));
    }

    @Test void ocrErrorsSaveNothing() {
        fails(ErrorCode.OCR_FAILED, () -> sendImage(UUID.randomUUID(), () -> { throw new com.wsid.common.error.ApiException(ErrorCode.OCR_FAILED); }));
        verifyNoInteractions(ai);
        assertTrue(saved.isEmpty());
    }
}
