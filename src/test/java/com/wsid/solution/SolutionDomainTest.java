package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.solution.domain.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SolutionDomainTest {
    static final ResponseCard TRANSFER = new ResponseCard("TRANSFER_RESPONSE", 1, CardPriority.URGENT,
            "송금 관련 우선 대응", List.of("POLICE", "BANK"));
    static final ResponseCard APP = new ResponseCard("MALICIOUS_APP_RESPONSE", 2, CardPriority.URGENT,
            "악성 앱 대응", List.of("POLICE"));
    static final ResponseCardCatalog CATALOG = code -> Optional.ofNullable(
            Map.of(TRANSFER.code(), TRANSFER, APP.code(), APP).get(code));

    static Map<String, String> facts(String transferred, String shared, String installed, String contact) {
        var map = new LinkedHashMap<String, String>();
        map.put("transferredMoney", transferred);
        map.put("sharedInformation", shared);
        map.put("installedApp", installed);
        map.put("contactContinues", contact);
        return map;
    }

    @Test
    void unknownHasAllFourKeysInPublicOrder() {
        var facts = SolutionFacts.unknown();
        assertEquals(List.of("transferredMoney", "sharedInformation", "installedApp", "contactContinues"),
                List.copyOf(facts.toKeyMap().keySet()));
        assertTrue(facts.toKeyMap().values().stream().allMatch("UNKNOWN"::equals));
        assertThrows(UnsupportedOperationException.class, () -> facts.toKeyMap().put("x", "YES"));
    }

    @Test
    void fromStringsAcceptsOnlyExactKeysAndValues() {
        assertEquals(FactValue.YES, SolutionFacts.fromStrings(facts("YES", "NO", "UNKNOWN", "NO"))
                .get(SolutionFact.TRANSFERRED_MONEY));
        var missing = facts("YES", "NO", "NO", "NO");
        missing.remove("installedApp");
        assertThrows(IllegalArgumentException.class, () -> SolutionFacts.fromStrings(missing));
        var extra = new HashMap<>(facts("YES", "NO", "NO", "NO"));
        extra.put("userId", "YES");
        assertThrows(IllegalArgumentException.class, () -> SolutionFacts.fromStrings(extra));
        var renamed = new HashMap<>(facts("YES", "NO", "NO", "NO"));
        renamed.remove("installedApp");
        renamed.put("송금", "YES");
        assertThrows(IllegalArgumentException.class, () -> SolutionFacts.fromStrings(renamed));
        for (String bad : new String[] {"yes", "예", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> SolutionFacts.fromStrings(facts(bad, "NO", "NO", "NO")));
        }
        assertThrows(IllegalArgumentException.class, () -> SolutionFacts.fromStrings(null));
    }

    @ParameterizedTest
    @CsvSource({
            "UNKNOWN,UNKNOWN,UNKNOWN,UNKNOWN,transferredMoney",
            "YES,UNKNOWN,UNKNOWN,NO,sharedInformation",
            "NO,NO,UNKNOWN,UNKNOWN,installedApp",
            "NO,YES,NO,UNKNOWN,contactContinues"})
    void questionFollowsFixedOrderOfFirstUnknown(String a, String b, String c, String d, String expected) {
        var question = SolutionQuestion.next(SolutionFacts.fromStrings(facts(a, b, c, d))).orElseThrow();
        assertEquals(expected, question.factKey());
        assertEquals(List.of("YES", "NO", "UNKNOWN"), question.options());
        assertFalse(question.content().isBlank());
    }

    @Test
    void specExampleAsksSharedInformationWithReviewedWording() {
        var question = SolutionQuestion.next(SolutionFacts.fromStrings(facts("YES", "UNKNOWN", "UNKNOWN", "NO")));
        assertEquals("개인정보를 전달한 적이 있나요?", question.orElseThrow().content());
    }

    @Test
    void allKnownFactsHaveNoQuestion() {
        assertTrue(SolutionQuestion.next(SolutionFacts.fromStrings(facts("NO", "NO", "NO", "YES"))).isEmpty());
    }

    @Test
    void rulesPickCardsOnlyForConfirmedYesAndKeepUrgentFirst() {
        assertEquals(List.of(), ResponseRules.cards(SolutionFacts.unknown(), CATALOG));
        assertEquals(List.of(), ResponseRules.cards(SolutionFacts.fromStrings(facts("NO", "YES", "NO", "YES")), CATALOG));
        assertEquals(List.of(TRANSFER), ResponseRules.cards(SolutionFacts.fromStrings(facts("YES", "UNKNOWN", "UNKNOWN", "NO")), CATALOG));
        assertEquals(List.of(TRANSFER, APP), ResponseRules.cards(SolutionFacts.fromStrings(facts("YES", "NO", "YES", "NO")), CATALOG));

        var normal = new ResponseCard("TRANSFER_RESPONSE", 1, CardPriority.NORMAL, "일반", List.of());
        ResponseCardCatalog mixed = code -> Optional.of(code.equals("TRANSFER_RESPONSE") ? normal : APP);
        assertEquals(List.of(APP, normal), ResponseRules.cards(SolutionFacts.fromStrings(facts("YES", "NO", "YES", "NO")), mixed));
    }

    @Test
    void missingReviewedCardIsNotSilentlyDropped() {
        var error = assertThrows(ResponseRules.MissingResponseCardException.class,
                () -> ResponseRules.cards(SolutionFacts.fromStrings(facts("YES", "NO", "NO", "NO")), code -> Optional.empty()));
        assertEquals("TRANSFER_RESPONSE", error.code());
    }

    @Test
    void cardRejectsInvalidContent() {
        assertThrows(IllegalArgumentException.class, () -> new ResponseCard("bad", 1, CardPriority.URGENT, "t", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResponseCard("A", 0, CardPriority.URGENT, "t", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResponseCard("A", 1, null, "t", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResponseCard("A", 1, CardPriority.URGENT, " ", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResponseCard("A", 1, CardPriority.URGENT, "t", List.of("x y")));
    }

    @Test
    void ownerHasExactlyOneIdentityAndHidesItInToString() {
        var anonymousId = UUID.randomUUID();
        assertTrue(SolutionOwner.member(7).isMember());
        assertFalse(SolutionOwner.anonymous(anonymousId).isMember());
        assertThrows(IllegalArgumentException.class, () -> new SolutionOwner(null, null));
        assertThrows(IllegalArgumentException.class, () -> new SolutionOwner(7L, anonymousId));
        assertFalse(SolutionOwner.anonymous(anonymousId).toString().contains(anonymousId.toString()));
        assertFalse(SolutionOwner.member(123456).toString().contains("123456"));
    }

    @Test
    void sessionStartStoresCanonicalJsonAndOwner() {
        var anonymousId = UUID.randomUUID();
        var facts = SolutionFacts.fromStrings(facts("YES", "UNKNOWN", "UNKNOWN", "NO"));
        var question = SolutionQuestion.next(facts).orElseThrow();
        var session = SolutionSession.start(SolutionOwner.anonymous(anonymousId), facts, List.of(TRANSFER), question, Instant.EPOCH);
        assertNull(session.getUserId());
        assertEquals(anonymousId, session.getAnonymousOwnerId());
        assertEquals(SolutionStatus.ACTIVE, session.getStatus());
        assertNull(session.getFraudTypeCode());
        assertEquals("{\"transferredMoney\":\"YES\",\"sharedInformation\":\"UNKNOWN\",\"installedApp\":\"UNKNOWN\",\"contactContinues\":\"NO\"}",
                session.getFactsJson());
        assertEquals("[{\"code\":\"TRANSFER_RESPONSE\",\"version\":1}]", session.getCardCodesJson());
        assertEquals("{\"factKey\":\"sharedInformation\",\"content\":\"개인정보를 전달한 적이 있나요?\",\"options\":[\"YES\",\"NO\",\"UNKNOWN\"]}",
                session.getNextQuestionJson());
        assertEquals(0, session.getStateVersion());

        var member = SolutionSession.start(SolutionOwner.member(5), SolutionFacts.fromStrings(facts("NO", "NO", "NO", "NO")),
                List.of(), null, Instant.EPOCH);
        assertEquals(5L, member.getUserId());
        assertNull(member.getAnonymousOwnerId());
        assertNull(member.getNextQuestionJson());
        assertEquals("[]", member.getCardCodesJson());
    }
}
