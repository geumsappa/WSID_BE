package com.wsid.ai;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.wsid.ai.dto.*;
import java.util.*;
class FakeAiClientDiscoveryTest {
 @Test void extractsAcrossFailedEarlierAnswersAndAcceptsCorrection() {
  var ai=new FakeAiClient();
  var request=new DiscoveryRequest(List.of(new DiscoveryTurn("user","중고거래를 했어요")),Map.of(),"노트북을 팔았어요",UUID.randomUUID());
  var r=ai.suggestDiscovery(request);assertTrue(r.canGenerateScenario());assertEquals("판매자",r.facts().get("role"));
  assertFalse(ai.suggestDiscovery(new DiscoveryRequest(List.of(),Map.of(),"중고거래",UUID.randomUUID())).canGenerateScenario());
  assertThrows(AiUnavailableException.class,()->ai.suggestDiscovery(new DiscoveryRequest(List.of(),Map.of(),"[AI_FAIL]",UUID.randomUUID())));
 }
}
