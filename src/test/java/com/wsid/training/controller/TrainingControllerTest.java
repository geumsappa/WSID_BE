package com.wsid.training.controller;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import com.wsid.training.service.TrainingService;
import com.wsid.training.dto.*;
import com.wsid.training.domain.*;
import com.wsid.common.domain.MessageSender;
import com.wsid.common.error.*;
class TrainingControllerTest {
 TrainingService service=mock(TrainingService.class);MockMvc mvc;
 String body="{\"requestId\":\"11111111-1111-1111-1111-111111111111\",\"stateVersion\":0,\"message\":\"중고거래\"}";
 @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new TrainingController(service),new LegacyTrainingController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();}
 @Test void discoveryUsesNewContract() throws Exception {
  when(service.submitDiscovery(eq(1L),any())).thenReturn(new DiscoveryResponse(1,1,TrainingPhase.DISCOVERY,DiscoveryStatus.ASKING,1,false,List.of(MessageDto.fromAi("질문"))));
  mvc.perform(post("/api/trainings/1/discovery/messages").contentType(MediaType.APPLICATION_JSON).content(body))
   .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].text").value("질문")).andExpect(jsonPath("$.questionCount").value(1));
 }
 @Test void emptyAndMalformedInput() throws Exception {
  for (String input:List.of("{}","{",body.replace("중고거래"," "),body.replace("중고거래","x".repeat(1001))))
   mvc.perform(post("/api/trainings/1/discovery/messages").contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  verifyNoInteractions(service);
 }
 @Test void phaseErrorsMapTo409() throws Exception {
  when(service.submitDiscovery(eq(1L),any())).thenThrow(new ApiException(ErrorCode.WRONG_PHASE));
  mvc.perform(post("/api/trainings/1/discovery/messages").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WRONG_PHASE"));
 }
 @Test void simulationAndLegacyRoutesUseDifferentEntryPoints() throws Exception {
  var result=new TurnResponse(1,2,TrainingPhase.SIMULATION,1,MessageDto.reviewed(MessageSender.AI,"첫 대사"),false,false);
  when(service.submitSimulationMessage(eq(1L),any())).thenReturn(result);when(service.submitTurn(eq(1L),any())).thenReturn(result);
  mvc.perform(post("/api/trainings/1/messages").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.message.text").value("첫 대사"));
  mvc.perform(post("/api/training/sessions/1/turns").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.message.text").value("첫 대사"));
  verify(service).submitTurn(eq(1L),any());verify(service).submitSimulationMessage(eq(1L),any());
 }
}
