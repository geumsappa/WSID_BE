package com.wsid.training.service;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.wsid.training.domain.ContextFacts;
import java.util.Map;
class DiscoveryQuestionsTest {
 @Test void priorityMatchesMissingFacts() {
  var f=ContextFacts.empty();assertEquals(ReviewedTexts.DISCOVERY_ACTIVITY,DiscoveryQuestions.fallbackFor(f));
  f=f.merge(Map.of("activity","중고거래"));assertEquals(ReviewedTexts.DISCOVERY_ROLE,DiscoveryQuestions.fallbackFor(f));
  f=f.merge(Map.of("role","판매자"));assertEquals(ReviewedTexts.DISCOVERY_CHANNEL,DiscoveryQuestions.fallbackFor(f));
  f=f.merge(Map.of("channel","문자"));assertEquals(ReviewedTexts.DISCOVERY_ITEM,DiscoveryQuestions.fallbackFor(f));
  f=f.merge(Map.of("item","노트북"));assertEquals(ReviewedTexts.DISCOVERY_OTHER,DiscoveryQuestions.fallbackFor(f));
 }
}
