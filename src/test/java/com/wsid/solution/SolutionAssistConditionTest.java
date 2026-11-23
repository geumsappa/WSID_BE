package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.wsid.ai.*;
import com.wsid.ai.dto.*;
import com.wsid.solution.content.ApprovedReferenceCatalog;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.*;
import com.wsid.solution.repository.*;
import com.wsid.solution.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;

/** v1.2 조건 질문: 질문 문장 없이 문단 코드·원문 인용·답만 저장하고, 네 사실·카드는 건드리지 않아요. */
class SolutionAssistConditionTest {
    static final String CODE = "REF_LAW_REFUND_PROCEDURE_V1_P2";
    static final String QUOTE = "제외: 물건·서비스를 주겠다며 속인 사기";
    static final String AI_QUESTION = "물건이나 서비스를 받기로 하고 보내신 돈인가요?";

    final SolutionAssistGateway ai = mock(SolutionAssistGateway.class);
    final SolutionSessionRepository sessions = mock(SolutionSessionRepository.class);
    final SolutionMessageRepository messages = mock(SolutionMessageRepository.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final SolutionOwner owner = SolutionOwner.anonymous(UUID.randomUUID());
    ApprovedReferenceCatalog references;
    SolutionSession session;
    SolutionAssistService service;

    static ApprovedReferenceCatalog catalog(String code, String excerpt) {
        String json = "[{\"code\":\"" + code + "\",\"version\":\"1\",\"title\":\"환급 절차\",\"excerpt\":\"" + excerpt
                + "\",\"source\":\"https://www.law.go.kr/\",\"checkedAt\":\"2026-11-01\",\"reviewed\":true}]";
        return new ApprovedReferenceCatalog(new ByteArrayResource(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @BeforeEach void setup() {
        references = catalog(CODE, "적용: 전화·문자로 속여 송금하게 한 경우.  제외: 물건·서비스를 주겠다며 속인 사기(중고거래 등).");
        var facts = SolutionFacts.unknown().withChoice(SolutionFact.TRANSFERRED_MONEY, FactValue.YES);
        session = SolutionSession.start(owner, facts, List.of(), SolutionQuestion.next(facts).orElse(null), Instant.now());
        ReflectionTestUtils.setField(session, "id", 301L);
        service = new SolutionAssistService(ai, new SensitiveDescriptionGuard(), references, SolutionDomainTest.CATALOG,
                sessions, messages, manager, Duration.ofSeconds(2), true);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(sessions.findOwned(301, owner)).thenReturn(Optional.of(session));
        when(sessions.compareAndSetAssist(eq(301L), eq(owner), anyInt(), any(), any())).thenAnswer(i -> {
            SolutionSession.Update u = i.getArgument(3);
            ReflectionTestUtils.setField(session, "factsJson", u.factsJson());
            ReflectionTestUtils.setField(session, "pendingAssistJson", i.getArgument(4));
            ReflectionTestUtils.setField(session, "stateVersion", (int) i.getArgument(2) + 1);
            return 1;
        });
    }

    SolutionMessageResponse send(SendSolutionMessageRequest r) { return service.send(301, r, () -> Optional.of(owner)); }

    static SolutionAssistPlanResult conditionQuestion(String quote) {
        return new SolutionAssistPlanResult(SolutionAssistStatus.NEED_USER_INPUT, SolutionAssistIntent.TRANSFER_RESPONSE,
                new SolutionAssistClarification(SolutionAssistClarification.Kind.CONDITION, null,
                        SolutionAssistCondition.of(CODE, quote), AI_QUESTION, SolutionAssistClarification.OPTIONS),
                List.of(), null);
    }

    @Test void conditionIdMatchesPythonHash() {
        // Python: assist.contracts.condition_id(CODE, QUOTE)
        assertEquals(CODE + "#e540a500e7ea", SolutionAssistCondition.of(CODE, QUOTE).conditionId());
        assertThrows(IllegalArgumentException.class, () -> new SolutionAssistCondition(CODE + "#e540a500e7ea", CODE, QUOTE + "(바뀜)"));
    }

    @Test void conditionQuestionStoresOnlyCodeQuoteAndShowsAiQuestionOnce() {
        when(ai.plan(any())).thenReturn(conditionQuestion(QUOTE));
        var factsBefore = session.readFacts().toKeyMap();
        var response = send(SolutionAssistServiceTest.parse("{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"stateVersion\":0,\"purpose\":\"QUESTION\",\"inputKind\":\"TEXT\",\"content\":\"돈을 돌려받을 수 있나요?\"}"));

        assertEquals(AI_QUESTION, response.assistant().question().content());
        assertNull(response.assistant().question().factKey());
        String stored = (String) ReflectionTestUtils.getField(session, "pendingAssistJson");
        assertTrue(stored.contains(QUOTE) && stored.contains(CODE) && stored.contains(AI_QUESTION)); // 답할 때까지만 보관
        var pending = session.readPendingAssist();
        assertTrue(pending.asksCondition()); assertNull(pending.fact()); assertEquals(2, pending.remainingClarifications());
        assertEquals(factsBefore, session.readFacts().toKeyMap());
        // 첫 요청부터 빈 목록을 보내 조건 질문을 켜요.
        verify(ai).plan(argThat(r -> r.conditionsEnabled() && r.confirmedConditions().isEmpty()));
        // 다시 조회해도 처음과 같은 문장으로 물어요.
        assertEquals(AI_QUESTION, SolutionAssistant.pending(pending).question().content());
        var saved = ArgumentCaptor.forClass(SolutionMessage.class);
        verify(messages).save(saved.capture());
        assertFalse(saved.getValue().getSafeContent().contains(AI_QUESTION), "메시지 기록에는 AI 문장을 남기지 않아요");
    }

    @Test void conditionAnswerGoesToConfirmedConditionsNotFactsAndReachesAnswer() {
        when(ai.plan(any())).thenReturn(conditionQuestion(QUOTE), new SolutionAssistPlanResult(SolutionAssistStatus.EVIDENCE_READY,
                SolutionAssistIntent.TRANSFER_RESPONSE, null, List.of(new SolutionCandidateReference(CODE, "1")), null));
        when(ai.answer(any())).thenReturn(new SolutionAssistAnswerResult(SolutionAssistStatus.ANSWERED, "안내입니다.", List.of(CODE), null));
        var first = send(SolutionAssistServiceTest.parse("{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"stateVersion\":0,\"purpose\":\"QUESTION\",\"inputKind\":\"TEXT\",\"content\":\"돈을 돌려받을 수 있나요?\"}"));
        var factsBefore = session.readFacts().toKeyMap();
        var second = send(SolutionAssistServiceTest.parse("{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"stateVersion\":1,\"purpose\":\"CLARIFICATION_ANSWER\",\"inputKind\":\"CHOICE\",\"questionId\":\""
                + first.assistant().question().questionId() + "\",\"selectedCode\":\"NO\"}"));

        assertEquals("안내입니다.", second.assistant().answer());
        assertEquals(factsBefore, second.facts());
        assertNull(session.readPendingAssist());
        assertNull(ReflectionTestUtils.getField(session, "pendingAssistJson")); // 답하면 AI 문장도 함께 지워져요
        var expected = List.of(new SolutionAssistConfirmedCondition(CODE + "#e540a500e7ea", CODE, QUOTE, "NO"));
        var plans = ArgumentCaptor.forClass(SolutionAssistPlanRequest.class);
        verify(ai, times(2)).plan(plans.capture());
        assertEquals(expected, plans.getAllValues().get(1).confirmedConditions());
        assertEquals(2, plans.getAllValues().get(1).remainingClarifications());
        assertEquals(List.of(), plans.getAllValues().get(1).askedFactKeys());
        verify(ai).answer(argThat(r -> expected.equals(r.confirmedConditions())));
        var saved = ArgumentCaptor.forClass(SolutionMessage.class);
        // 질문 행, 조건 답 행, AI 답변 행
        verify(messages, times(3)).save(saved.capture());
        var answer = saved.getAllValues().get(1);
        assertNull(answer.getFactKey()); assertEquals("NO", answer.getSelectedCode());
        // 무엇을 물었고 어떻게 답했는지 새로고침 때 보이게 남겨요.
        assertEquals(AI_QUESTION + " → 아니요", answer.getSafeContent());
        assertEquals(com.wsid.common.domain.MessageSender.AI, saved.getAllValues().get(2).getSender());
        // 조건 근거 코드와 이 턴 답변이 인용한 코드가 함께 남아요(중복 제거).
        assertEquals("[\"" + CODE + "\"]", ReflectionTestUtils.getField(answer, "referenceCodesJson"));
    }

    @Test void flagOffSendsNoConditionsLikeV1() {
        service = new SolutionAssistService(ai, new SensitiveDescriptionGuard(), references, SolutionDomainTest.CATALOG,
                sessions, messages, manager, Duration.ofSeconds(2), false);
        when(ai.plan(any())).thenReturn(new SolutionAssistPlanResult(SolutionAssistStatus.UNABLE_TO_ANSWER, null, null,
                List.of(), SolutionAssistReason.OUT_OF_SCOPE));
        send(SolutionAssistServiceTest.parse("{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"stateVersion\":0,\"purpose\":\"QUESTION\",\"inputKind\":\"TEXT\",\"content\":\"돈을 돌려받을 수 있나요?\"}"));
        verify(ai).plan(argThat(r -> !r.conditionsEnabled()));
    }

    @Test void conditionWhoseQuoteIsNotInApprovedExcerptIsNotAsked() {
        when(ai.plan(any())).thenReturn(conditionQuestion("제외: 중고거래는 무조건 환급 불가"));
        var response = send(SolutionAssistServiceTest.parse("{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"stateVersion\":0,\"purpose\":\"QUESTION\",\"inputKind\":\"TEXT\",\"content\":\"돈을 돌려받을 수 있나요?\"}"));
        assertEquals(SolutionAssistReason.NO_EVIDENCE, response.assistant().reasonCode());
        assertNull(session.readPendingAssist());
    }

    @Test void catalogQuoteCheckNormalizesWhitespaceLikePython() {
        assertTrue(references.containsQuote(CODE, "적용: 전화·문자로 속여 송금하게 한 경우. 제외:"));
        assertFalse(references.containsQuote("OTHER_CODE", QUOTE));
        assertFalse(references.containsQuote(CODE, "제외"));
    }

    @Test void legacyFivefieldPendingIsStillReadable() {
        ReflectionTestUtils.setField(session, "pendingAssistJson", "{\"questionId\":\"" + UUID.randomUUID()
                + "\",\"fact\":\"INSTALLED_APP\",\"questionIntent\":\"DEVICE_SAFETY\",\"askedFactKeys\":[\"installedApp\"],\"remainingClarifications\":2}");
        var pending = session.readPendingAssist();
        assertEquals(SolutionFact.INSTALLED_APP, pending.fact()); assertFalse(pending.asksCondition());
        assertEquals(List.of(), pending.confirmedConditions());
    }

    @Test void validatorRejectsConditionForV1RequestAndAlreadyConfirmed() {
        var plan = conditionQuestion(QUOTE);
        var v1 = new SolutionAssistPlanRequest("질문", null, SolutionFacts.unknown().toKeyMap(), List.of(), 3, UUID.randomUUID());
        assertThrows(AiUnavailableException.class, () -> SolutionAssistValidator.validate(v1, plan));
        var done = List.of(SolutionAssistConfirmedCondition.of(SolutionAssistCondition.of(CODE, QUOTE), "YES"));
        var again = new SolutionAssistPlanRequest(null, SolutionAssistIntent.TRANSFER_RESPONSE, SolutionFacts.unknown().toKeyMap(),
                List.of(), 2, UUID.randomUUID(), null, null, done);
        assertThrows(AiUnavailableException.class, () -> SolutionAssistValidator.validate(again, plan));
        var enabled = new SolutionAssistPlanRequest("질문", null, SolutionFacts.unknown().toKeyMap(), List.of(), 3,
                UUID.randomUUID(), null, null, List.of());
        assertSame(plan, SolutionAssistValidator.validate(enabled, plan));
    }
}
