package com.wsid.ai;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.time.Duration;
import java.util.*;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.wsid.ai.dto.*;
class HttpAiClientDiscoveryTest {
 private final RestClient.Builder builder=RestClient.builder();
 private final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
 private final HttpAiClient client=new HttpAiClient(new AiProperties("http","http://ai.test","test-key",Duration.ofSeconds(2),Duration.ofSeconds(20)),builder);
 private final UUID id=UUID.randomUUID();
 private DiscoveryRequest request(){return new DiscoveryRequest(List.of(new DiscoveryTurn("assistant","질문")),Map.of("activity","중고거래"),"판매자",id);}
 @Test void envelopeAndRequestContract(){
  server.expect(requestTo("http://ai.test/internal/ai/discovery/next"))
   .andExpect(method(HttpMethod.POST)).andExpect(header("X-Internal-Api-Key","test-key")).andExpect(header("X-Request-Id",id.toString()))
   .andExpect(content().json("{\"conversation\":[{\"role\":\"assistant\",\"content\":\"질문\"}],\"knownFacts\":{\"activity\":\"중고거래\"},\"currentInput\":\"판매자\"}"))
   .andRespond(withSuccess("{\"success\":true,\"data\":{\"facts\":{\"role\":\"판매자\"},\"nextQuestion\":\"연락은 어떻게 했나요?\",\"canGenerateScenario\":true,\"extra\":1}}",MediaType.APPLICATION_JSON));
  assertTrue(client.suggestDiscovery(request()).canGenerateScenario());server.verify();
 }
 @ParameterizedTest @ValueSource(strings={"", "{", "{}", "{\"success\":false}", "{\"success\":true}",
 "{\"success\":true,\"data\":{\"facts\":{},\"canGenerateScenario\":false}}", "{\"success\":true,\"data\":{\"facts\":{},\"nextQuestion\":\"질문\"}}"})
 void invalidResponses(String body){server.expect(anything()).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));assertThrows(AiUnavailableException.class,()->client.suggestDiscovery(request()));server.verify();}
 @Test void httpFailureDoesNotRetainResponseBody(){server.expect(anything()).andRespond(withServerError().body("secret-user-input"));var e=assertThrows(AiUnavailableException.class,()->client.suggestDiscovery(request()));assertNull(e.getCause());assertFalse(e.getMessage().contains("secret-user-input"));}
 @Test void timeout(){server.expect(anything()).andRespond(withException(new SocketTimeoutException("timeout")));assertThrows(AiUnavailableException.class,()->client.suggestDiscovery(request()));}
 @Test void nestedFactsAreFilteredWithoutRejectingWholeResponse(){server.expect(anything()).andRespond(withSuccess("{\"success\":true,\"data\":{\"facts\":{\"role\":{},\"activity\":\"중고거래\"},\"nextQuestion\":\"질문\",\"canGenerateScenario\":false}}",MediaType.APPLICATION_JSON));var r=client.suggestDiscovery(request());assertEquals(Map.of("activity","중고거래"),com.wsid.training.domain.ContextFacts.empty().merge(r.facts()).values());}
 @Test void rejectsCoercedBooleansAndLongQuestion(){
  for(String body:List.of(
    "{\"success\":true,\"data\":{\"facts\":{},\"nextQuestion\":\"질문\",\"canGenerateScenario\":\"true\"}}",
    "{\"success\":true,\"data\":{\"facts\":{},\"nextQuestion\":\""+"x".repeat(201)+"\",\"canGenerateScenario\":true}}")) {
   server.reset();server.expect(anything()).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));assertThrows(AiUnavailableException.class,()->client.suggestDiscovery(request()));server.verify();
  }
 }
}
