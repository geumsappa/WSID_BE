package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.wsid.ai.*;
import com.wsid.ai.dto.*;
import com.wsid.common.error.*;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.*;
import com.wsid.solution.service.*;
import com.wsid.solution.repository.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@Tag("db")
@SpringBootTest(properties="spring.datasource.url=${WSID_TEST_DB_URL:jdbc:postgresql://localhost:5432/wsid_ai01_test}")
class SolutionAssistDatabaseTest {
    @Autowired StartSolutionService starts;
    @Autowired SendSolutionMessageService messages;
    @Autowired SolutionQueryService queries;
    @Autowired UpdateSolutionFactsService corrections;
    @Autowired CompleteSolutionService completes;
    @Autowired JdbcTemplate jdbc;
    @Autowired SolutionSessionRepository sessions;
    @MockitoBean SolutionAssistGateway ai;
    @MockitoBean ResponseCardCatalog cards;
    final SolutionOwner owner=SolutionOwner.anonymous(UUID.randomUUID());
    long id;
    @BeforeEach void setup() {
        when(cards.findReviewed(anyString())).thenAnswer(i->SolutionDomainTest.CATALOG.findReviewed(i.getArgument(0)));
        id=starts.start(StartSolutionRequest.empty(),()->owner).solutionId();
        when(ai.plan(any())).thenReturn(new SolutionAssistPlanResult(SolutionAssistStatus.NEED_USER_INPUT,SolutionAssistIntent.DEVICE_SAFETY,
                new SolutionAssistClarification("installedApp","고정 질문",List.of("YES","NO","UNKNOWN")),List.of(),null));
    }
    @AfterEach void cleanup() {jdbc.update("DELETE FROM solution_messages WHERE solution_id=?",id);jdbc.update("DELETE FROM solution_sessions WHERE id=?",id);}
    SendSolutionMessageRequest question(int version,UUID requestId) {
        return SendSolutionMessageRequest.from(new ObjectMapper().readTree("{\"requestId\":\""+requestId+"\",\"stateVersion\":"+version+",\"purpose\":\"QUESTION\",\"inputKind\":\"TEXT\",\"content\":\"원문저장검증표식\"}"));
    }
    SolutionMessageResponse send(SendSolutionMessageRequest input) {return messages.send(id,input,()->Optional.of(owner));}
    @Test void pendingRoundTripsAndCompletionClearsIt() {
        var response=send(question(0,UUID.randomUUID()));
        var restored=queries.get(id,()->Optional.of(owner));
        assertEquals(response.assistant(),restored.assistant());assertNull(restored.nextQuestion());
        assertEquals(2,sessions.findOwned(id,owner).orElseThrow().readPendingAssist().remainingClarifications());
        // 질문 원문은 질문 행 하나에만 남고(새로고침 복원), 확인 질문 대기 중이라 AI 행은 없어요.
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id=? AND safe_content='원문저장검증표식' AND purpose='QUESTION'",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id=? AND sender='AI'",Integer.class,id));
        completes.complete(id,()->Optional.of(owner));
        var done=queries.get(id,()->Optional.of(owner));assertNull(done.assistant());assertNull(done.nextQuestion());
        assertNull(sessions.findOwned(id,owner).orElseThrow().readPendingAssist());
    }
    @Test void choiceCommitsCardsFactAndBudgetTogether() {
        var first=send(question(0,UUID.randomUUID()));
        when(ai.plan(any())).thenReturn(new SolutionAssistPlanResult(SolutionAssistStatus.EVIDENCE_READY,SolutionAssistIntent.DEVICE_SAFETY,
                null,List.of(new SolutionCandidateReference("NO_APPROVED_REF","1")),null));
        var input=SendSolutionMessageRequest.from(new ObjectMapper().readTree("{\"requestId\":\""+UUID.randomUUID()+"\",\"stateVersion\":1,\"purpose\":\"CLARIFICATION_ANSWER\",\"inputKind\":\"CHOICE\",\"questionId\":\""+first.assistant().question().questionId()+"\",\"selectedCode\":\"YES\"}"));
        var second=send(input);assertEquals(2,second.stateVersion());assertEquals("YES",second.facts().get("installedApp"));
        assertEquals("MALICIOUS_APP_RESPONSE",second.responseCards().getFirst().code());
        assertEquals(SolutionAssistReason.NO_EVIDENCE,second.assistant().reasonCode());
        assertNull(sessions.findOwned(id,owner).orElseThrow().readPendingAssist());verify(ai,never()).answer(any());
        assertEquals(ErrorCode.DUPLICATE_REQUEST,assertThrows(ApiException.class,()->send(input)).errorCode());
    }
    @Test void aiFailureLeavesVersionAndPendingUnchanged() {
        when(ai.plan(any())).thenThrow(new AiUnavailableException("down"));
        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE,assertThrows(ApiException.class,()->send(question(0,UUID.randomUUID()))).errorCode());
        assertEquals(0,sessions.findOwned(id,owner).orElseThrow().getStateVersion());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id=?",Integer.class,id));
    }
    @Test void factUpdateAndCorrectionInvalidatePending() {
        send(question(0,UUID.randomUUID()));
        corrections.update(id,UpdateSolutionFactsRequest.from(new ObjectMapper().readTree("{\"stateVersion\":1,\"changes\":{\"installedApp\":\"NO\"}}")),()->Optional.of(owner));
        assertNull(sessions.findOwned(id,owner).orElseThrow().readPendingAssist());
        when(ai.plan(any())).thenReturn(new SolutionAssistPlanResult(SolutionAssistStatus.NEED_USER_INPUT,SolutionAssistIntent.DEVICE_SAFETY,
                new SolutionAssistClarification("sharedInformation","고정 질문",List.of("YES","NO","UNKNOWN")),List.of(),null));
        send(question(2,UUID.randomUUID()));
        var text=SendSolutionMessageRequest.from(SendSolutionMessageRequestTest.choice(UUID.randomUUID(),3,"transferredMoney","NO"));
        send(text);assertNull(sessions.findOwned(id,owner).orElseThrow().readPendingAssist());
    }
    @Test void twoSameVersionRequestsOnlyOneCommits() throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        when(ai.plan(any())).thenAnswer(i->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));
            return new SolutionAssistPlanResult(SolutionAssistStatus.UNABLE_TO_ANSWER,SolutionAssistIntent.GENERAL,null,List.of(),SolutionAssistReason.NO_EVIDENCE);});
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Object> call=()->{try{return send(question(0,UUID.randomUUID()));}catch(ApiException e){return e.errorCode();}};
            var a=pool.submit(call);var b=pool.submit(call);assertTrue(entered.await(5,TimeUnit.SECONDS));release.countDown();
            var results=List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(SolutionMessageResponse.class::isInstance).count());
            assertTrue(results.contains(ErrorCode.STATE_VERSION_CONFLICT));
            assertEquals(1,sessions.findOwned(id,owner).orElseThrow().getStateVersion());
        } finally {release.countDown();}
    }
}
