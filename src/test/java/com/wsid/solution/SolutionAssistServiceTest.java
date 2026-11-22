package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.wsid.ai.*;
import com.wsid.ai.dto.*;
import com.wsid.common.error.*;
import com.wsid.solution.content.ApprovedReferenceCatalog;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.*;
import com.wsid.solution.repository.*;
import com.wsid.solution.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import tools.jackson.databind.ObjectMapper;

class SolutionAssistServiceTest {
    final SolutionAssistGateway ai = mock(SolutionAssistGateway.class);
    final ApprovedReferenceCatalog references = mock(ApprovedReferenceCatalog.class);
    final SolutionSessionRepository sessions = mock(SolutionSessionRepository.class);
    final SolutionMessageRepository messages = mock(SolutionMessageRepository.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final SolutionOwner owner = SolutionOwner.anonymous(UUID.randomUUID());
    final AtomicBoolean txActive = new AtomicBoolean();
    SolutionSession session;
    SolutionAssistService service;
    @BeforeEach void setup() {
        session = SolutionSession.start(owner, SolutionFacts.unknown(), List.of(), SolutionQuestion.next(SolutionFacts.unknown()).orElseThrow(), Instant.now());
        ReflectionTestUtils.setField(session, "id", 301L);
        service = new SolutionAssistService(ai, new SensitiveDescriptionGuard(), references, SolutionDomainTest.CATALOG,
                sessions, messages, manager, Duration.ofSeconds(2));
        when(manager.getTransaction(any())).thenAnswer(i -> {txActive.set(true); return mock(TransactionStatus.class);});
        doAnswer(i -> {txActive.set(false); return null;}).when(manager).commit(any());
        doAnswer(i -> {txActive.set(false); return null;}).when(manager).rollback(any());
        when(sessions.findOwned(301, owner)).thenReturn(Optional.of(session));
        when(sessions.compareAndSetAssist(eq(301L), eq(owner), anyInt(), any(), any())).thenAnswer(i -> {
            assertTrue(txActive.get());
            SolutionSession.Update u=i.getArgument(3);
            ReflectionTestUtils.setField(session,"factsJson",u.factsJson());
            ReflectionTestUtils.setField(session,"nextQuestionJson",u.questionJson());
            ReflectionTestUtils.setField(session,"pendingAssistJson",i.getArgument(4));
            ReflectionTestUtils.setField(session,"stateVersion",(int)i.getArgument(2)+1);
            return 1;
        });
    }
    SendSolutionMessageRequest question(int version) {
        return parse("{\"requestId\":\""+UUID.randomUUID()+"\",\"stateVersion\":"+version+",\"purpose\":\"QUESTION\",\"inputKind\":\"TEXT\",\"content\":\"앱 관련 질문입니다\"}");
    }
    SendSolutionMessageRequest choice(int version, UUID id, String selected) {
        return parse("{\"requestId\":\""+UUID.randomUUID()+"\",\"stateVersion\":"+version+",\"purpose\":\"CLARIFICATION_ANSWER\",\"inputKind\":\"CHOICE\",\"questionId\":\""+id+"\",\"selectedCode\":\""+selected+"\"}");
    }
    static SendSolutionMessageRequest parse(String json) {return SendSolutionMessageRequest.from(new ObjectMapper().readTree(json));}
    SolutionMessageResponse send(SendSolutionMessageRequest r) {return service.send(301,r,()->Optional.of(owner));}
    SolutionAssistPlanResult clarification(String key) {return new SolutionAssistPlanResult(SolutionAssistStatus.NEED_USER_INPUT,
        SolutionAssistIntent.DEVICE_SAFETY,new SolutionAssistClarification(key,"모델 문구",List.of("YES","NO","UNKNOWN")),List.of(),null);}
    SolutionAssistPlanResult evidence() {return new SolutionAssistPlanResult(SolutionAssistStatus.EVIDENCE_READY,
        SolutionAssistIntent.DEVICE_SAFETY,null,List.of(new SolutionCandidateReference("APP_GUIDE","1")),null);}
    void fails(ErrorCode c, Runnable r) {assertEquals(c,assertThrows(ApiException.class,r::run).errorCode());}
    @Test void questionUsesAiOutsideTransactionAndStoresOnlyStructuredPending() {
        when(ai.plan(any())).thenAnswer(i->{assertFalse(txActive.get()); return clarification("installedApp");});
        var response=send(question(0));
        assertEquals(1,response.stateVersion()); assertNull(response.nextQuestion());
        assertEquals(SolutionFact.INSTALLED_APP.question(),response.assistant().question().content());
        var pending=session.readPendingAssist();
        assertEquals(2,pending.remainingClarifications());assertEquals(List.of("installedApp"),pending.askedFactKeys());
        assertEquals(SolutionAssistIntent.DEVICE_SAFETY,pending.questionIntent());
        var capture=org.mockito.ArgumentCaptor.forClass(SolutionMessage.class);verify(messages).save(capture.capture());
        assertFalse(capture.getValue().getSafeContent().contains("앱 관련 질문"));
    }
    @Test void choiceUpdatesFactAndCardsAndKeepsOriginalIntentWithoutOriginalText() {
        when(ai.plan(any())).thenReturn(clarification("installedApp"),evidence());
        var first=send(question(0));
        when(references.approved(any())).thenReturn(List.of());
        var second=send(choice(1,first.assistant().question().questionId(),"YES"));
        assertEquals("YES",second.facts().get("installedApp"));assertEquals(1,second.responseCards().size());
        assertEquals(SolutionAssistReason.NO_EVIDENCE,second.assistant().reasonCode());assertNull(session.readPendingAssist());
        var requests=org.mockito.ArgumentCaptor.forClass(SolutionAssistPlanRequest.class);verify(ai,times(2)).plan(requests.capture());
        var followup=requests.getAllValues().get(1);assertNull(followup.question());
        assertEquals(List.of("installedApp"),followup.askedFactKeys());assertEquals(2,followup.remainingClarifications());
        verify(ai,never()).answer(any());
    }
    @Test void approvedReferencesOnlyArePassedToAnswer() {
        when(ai.plan(any())).thenReturn(evidence());
        var ref=new VerifiedReference("APP_GUIDE","검토 제목","검토 문단");when(references.approved(any())).thenReturn(List.of(ref));
        when(ai.answer(any())).thenReturn(new SolutionAssistAnswerResult(SolutionAssistStatus.ANSWERED,"안내입니다.",List.of("APP_GUIDE"),null));
        var result=send(question(0));assertEquals("안내입니다.",result.assistant().answer());
        verify(ai).answer(argThat(r->r.verifiedReferences().equals(List.of(ref))));
    }
    @Test void aiFailureDoesNotPersistAnything() {
        when(ai.plan(any())).thenThrow(new AiUnavailableException("failure"));fails(ErrorCode.AI_SERVICE_UNAVAILABLE,()->send(question(0)));
        verify(sessions,never()).compareAndSetAssist(anyLong(),any(),anyInt(),any(),any());verify(messages,never()).save(any());
    }
    @Test void totalDeadlineDoesNotPersistLateResult() {
        service=new SolutionAssistService(ai,new SensitiveDescriptionGuard(),references,SolutionDomainTest.CATALOG,sessions,messages,manager,Duration.ofMillis(50));
        when(ai.plan(any())).thenAnswer(i->{Thread.sleep(500);return evidence();});
        fails(ErrorCode.AI_SERVICE_UNAVAILABLE,()->send(question(0)));verify(messages,never()).save(any());
    }
    @Test void anotherQuestionIsRejectedWhilePending() {
        when(ai.plan(any())).thenReturn(clarification("installedApp"));send(question(0));
        fails(ErrorCode.ASSIST_QUESTION_PENDING,()->send(question(1)));verify(ai,times(1)).plan(any());
    }
    @Test void staleQuestionAndMissingOwnerAreRejectedBeforeAi() {
        fails(ErrorCode.INVALID_REQUEST,()->send(choice(0,UUID.randomUUID(),"YES")));
        fails(ErrorCode.SOLUTION_NOT_FOUND,()->service.send(301,question(0),Optional::empty));verifyNoInteractions(ai);
    }
    @Test void concurrentVersionChangeDiscardsAiResult() {
        when(ai.plan(any())).thenAnswer(i->{ReflectionTestUtils.setField(session,"stateVersion",1);return evidence();});
        fails(ErrorCode.STATE_VERSION_CONFLICT,()->send(question(0)));verify(messages,never()).save(any());
    }
    @Test void duplicateAndCompletedSessionAreRejectedBeforeAi() {
        var request=question(0);when(messages.existsBySolutionIdAndClientRequestId(301L,request.requestId())).thenReturn(true);
        fails(ErrorCode.DUPLICATE_REQUEST,()->send(request));
        when(messages.existsBySolutionIdAndClientRequestId(anyLong(),any())).thenReturn(false);
        ReflectionTestUtils.setField(session,"status",SolutionStatus.COMPLETED);
        fails(ErrorCode.SOLUTION_FINISHED,()->send(question(0)));verifyNoInteractions(ai);
    }
}
