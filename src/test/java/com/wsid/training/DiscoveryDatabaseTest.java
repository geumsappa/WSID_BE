package com.wsid.training;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import com.wsid.ai.*;
import com.wsid.ai.dto.*;
import com.wsid.auth.*;
import com.wsid.auth.domain.User;
import com.wsid.auth.repository.UserRepository;
import com.wsid.training.service.TrainingService;
import com.wsid.training.dto.*;
import com.wsid.training.domain.*;
import com.wsid.training.repository.*;
import com.wsid.common.error.*;

@Tag("db")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
 properties="spring.datasource.url=${WSID_TEST_DB_URL:jdbc:postgresql://localhost:5432/wsid_ai01_test}")
class DiscoveryDatabaseTest {
 @Autowired TrainingService service;
 @Autowired TrainingSessionRepository sessions;
 @Autowired TrainingMessageRepository messages;
 @Autowired UserRepository users;
 @Autowired JdbcTemplate jdbc;
 @MockitoBean AiClient ai;
 @MockitoBean CurrentUser current;
 long userId;
 @BeforeEach void setup(){
  reset(ai,current);
  userId=users.saveAndFlush(User.localDev(UUID.randomUUID()+"@test.invalid",Instant.now())).getId();
  when(current.id()).thenReturn(userId);
  reset(ai);
  when(ai.suggestDiscovery(any())).thenAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return new FakeAiClient().suggestDiscovery(i.getArgument(0));});
  when(ai.suggestTrainingTurn(any())).thenAnswer(i->new FakeAiClient().suggestTrainingTurn(i.getArgument(0)));
 }
 private SubmitTurnRequest request(int version,String message){return new SubmitTurnRequest(UUID.randomUUID(),version,message);}
 @Test void factsRoundTripOpeningHistoryAndPhase() {
  var start=service.start();long id=start.sessionId();
  var a=service.submitDiscovery(id,request(0,"중고거래"));assertEquals(1,a.questionCount());
  assertThrows(ApiException.class,()->service.submitSimulationMessage(id,request(a.stateVersion(),"답변")));
  var b=service.submitDiscovery(id,request(a.stateVersion(),"노트북을 팔았어요"));assertEquals(2,b.questionCount());
  var c=service.submitDiscovery(id,request(b.stateVersion(),"문자"));
  assertEquals(DiscoveryStatus.READY,c.discoveryStatus());assertEquals(TrainingPhase.SIMULATION,c.phase());assertEquals(2,c.messages().size());assertEquals(2,c.questionCount());
  var stored=sessions.findById(id).orElseThrow();assertEquals(3,stored.getDiscoveryTurnCount());assertEquals("판매자",stored.getContextFacts().values().get("role"));assertEquals(1,stored.getSimulationTurnCount());
  var history=service.getState(id);assertEquals(8,history.messages().size());assertEquals(DiscoveryStatus.READY,history.discoveryStatus());assertEquals(2,history.questionCount());
  var phase=assertThrows(ApiException.class,()->service.submitDiscovery(id,request(c.stateVersion(),"답변")));assertEquals(ErrorCode.WRONG_PHASE,phase.errorCode());
  service.submitSimulationMessage(id,request(c.stateVersion(),"질문할게요"));
 }
 @Test void repeatedFailuresStopAtSixWithoutAnotherQuestion() {
  var s=service.start();int version=s.stateVersion();
  for(int i=1;i<=6;i++){
   var a=service.submitDiscovery(s.sessionId(),request(version,"[AI_FAIL]"));version=a.stateVersion();assertTrue(a.fallback());
   assertEquals(i<6?DiscoveryStatus.ASKING:DiscoveryStatus.DEFAULT_SCENARIO,a.discoveryStatus());assertEquals(Math.min(i,5),a.questionCount());assertEquals(i<6?1:2,a.messages().size());
  }
  assertEquals(14,service.getState(s.sessionId()).messages().size());
 }
 @Test void lastFailureWithPreviouslyStoredFactsStillReady() {
  reset(ai);
  when(ai.suggestDiscovery(any())).thenReturn(new DiscoveryResult(Map.of("activity","중고거래","role","판매자"),"다음 질문",false));
  var s=service.start();var a=service.submitDiscovery(s.sessionId(),request(0,"가상 상황"));
  reset(ai);
  when(ai.suggestDiscovery(any())).thenThrow(new AiUnavailableException("fake"));
  for(int i=2;i<=6;i++) a=service.submitDiscovery(s.sessionId(),request(a.stateVersion(),"가상 답변"));
  assertEquals(DiscoveryStatus.READY,a.discoveryStatus());assertEquals(5,a.questionCount());assertEquals(2,a.messages().size());
 }
 @Test void staleDuplicateAndOtherOwnerDoNotCallAi(){
  var s=service.start();var req=request(0,"중고거래");service.submitDiscovery(s.sessionId(),req);clearInvocations(ai);
  assertEquals(ErrorCode.DUPLICATE_REQUEST,assertThrows(ApiException.class,()->service.submitDiscovery(s.sessionId(),req)).errorCode());
  assertEquals(ErrorCode.SESSION_VERSION_CONFLICT,assertThrows(ApiException.class,()->service.submitDiscovery(s.sessionId(),request(0,"다시"))).errorCode());
  when(current.id()).thenReturn(userId+100000);
  assertEquals(ErrorCode.SESSION_NOT_FOUND,assertThrows(ApiException.class,()->service.submitDiscovery(s.sessionId(),request(1,"다른 사용자"))).errorCode());verifyNoInteractions(ai);
 }
 @Test void stateChangeDuringAiLeavesNoAnswerSaved(){
  for(String change:List.of("version","expired","finished")){
   var s=service.start();long id=s.sessionId();
   reset(ai);
  when(ai.suggestDiscovery(any())).thenAnswer(i->{
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    switch(change){
     case "version"->jdbc.update("update training_sessions set state_version=state_version+1 where id=?",id);
     case "expired"->jdbc.update("update training_sessions set expires_at=now()-interval '1 hour' where id=?",id);
     case "finished"->jdbc.update("update training_sessions set status='COMPLETED' where id=?",id);
    }
    return new DiscoveryResult(Map.of(),"질문",false);
   });
   var error=assertThrows(ApiException.class,()->service.submitDiscovery(id,request(0,"가상 답변")));
   assertEquals(switch(change){case "version"->ErrorCode.SESSION_VERSION_CONFLICT;case "expired"->ErrorCode.SESSION_EXPIRED;default->ErrorCode.SESSION_FINISHED;},error.errorCode());assertEquals(1,messages.countByTrainingId(id));
  }
 }
 @Test void legacyDispatcherPreservesOldResponseShape(){
  var s=service.start();var a=service.submitTurn(s.sessionId(),request(0,"중고거래"));assertEquals(TrainingPhase.DISCOVERY,a.phase());
  a=service.submitTurn(s.sessionId(),request(a.stateVersion(),"팔았어요"));
  a=service.submitTurn(s.sessionId(),request(a.stateVersion(),"문자"));assertEquals(TrainingPhase.SIMULATION,a.phase());assertEquals(1,a.simulationTurnCount());assertTrue(a.message().text().contains("판매"));
  a=service.submitTurn(s.sessionId(),request(a.stateVersion(),"질문"));assertEquals(2,a.simulationTurnCount());
 }
 @Test void concurrentRequestsStoreOnce() throws Exception {
  for(boolean sameId:List.of(true,false)){
   var start=service.start();long id=start.sessionId();var barrier=new CyclicBarrier(2);
   reset(ai);
  when(ai.suggestDiscovery(any())).thenAnswer(i->{barrier.await(10,TimeUnit.SECONDS);return new DiscoveryResult(Map.of(),"다음 질문",false);});
   var a=request(0,"첫 가상 요청");var b=sameId?a:request(0,"다른 가상 요청");
   try(var pool=Executors.newFixedThreadPool(2)){
    var f1=pool.submit(()->outcome(id,a));var f2=pool.submit(()->outcome(id,b));
    var results=List.of(f1.get(20,TimeUnit.SECONDS),f2.get(20,TimeUnit.SECONDS));
    assertEquals(1,results.stream().filter("OK"::equals).count(),results.toString());
    assertTrue(results.contains(sameId?"DUPLICATE_REQUEST":"SESSION_VERSION_CONFLICT"),results.toString());
   }
   assertEquals(3,messages.countByTrainingId(id));assertEquals(1,sessions.findById(id).orElseThrow().getDiscoveryTurnCount());
  }
 }
 private String outcome(long id,SubmitTurnRequest request){
  try{service.submitDiscovery(id,request);return "OK";}
  catch(ApiException e){return e.errorCode().name();}
  catch(DataIntegrityViolationException e){return new GlobalExceptionHandler().handleIntegrity(e).getBody().code();}
  catch(ObjectOptimisticLockingFailureException e){return "SESSION_VERSION_CONFLICT";}
 }
}
