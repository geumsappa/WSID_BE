package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import com.wsid.common.domain.InputKind;
import com.wsid.solution.domain.*;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SolutionMessageRulesTest {
    @ParameterizedTest @CsvSource({"UNKNOWN,YES,YES,0", "UNKNOWN,NO,NO,0", "UNKNOWN,UNKNOWN,UNKNOWN,0",
            "YES,NO,YES,1", "NO,YES,NO,1", "YES,UNKNOWN,YES,0", "NO,UNKNOWN,NO,0", "YES,YES,YES,0", "NO,NO,NO,0"})
    void onlyUnknownIsFilled(String old, String proposed, String expected, int conflicts) {
        var original = SolutionFacts.unknown().withChoice(SolutionFact.SHARED_INFORMATION, FactValue.valueOf(old));
        var incoming = SolutionFacts.unknown().withChoice(SolutionFact.SHARED_INFORMATION, FactValue.valueOf(proposed));
        var merge = original.mergeExtracted(incoming);
        assertEquals(expected, merge.facts().get(SolutionFact.SHARED_INFORMATION).name());
        assertEquals(conflicts, merge.conflictCount());
        assertEquals(old, original.get(SolutionFact.SHARED_INFORMATION).name());
    }

    @Test void sendsOnlyConfirmedFactsAndKeepsMapImmutable() {
        var facts = SolutionFacts.unknown().withChoice(SolutionFact.TRANSFERRED_MONEY, FactValue.YES)
                .withChoice(SolutionFact.CONTACT_CONTINUES, FactValue.NO);
        assertEquals(Map.of("transferredMoney", "YES", "contactContinues", "NO"), facts.knownValues());
        assertThrows(UnsupportedOperationException.class, () -> facts.knownValues().put("installedApp", "YES"));
        assertTrue(SolutionFacts.unknown().knownValues().isEmpty());
    }

    @Test void skipsUnknownChoicesWithoutInventingNoOrCompletingTheSession() {
        var facts = SolutionFacts.unknown();
        assertEquals("sharedInformation", SolutionQuestion.next(facts, Set.of(SolutionFact.TRANSFERRED_MONEY)).orElseThrow().factKey());
        assertTrue(SolutionQuestion.next(facts, Set.of(SolutionFact.values())).isEmpty());
        assertEquals("transferredMoney", SolutionQuestion.next(facts).orElseThrow().factKey());
        assertEquals("UNKNOWN", facts.toKeyMap().get("transferredMoney"));
    }

    @Test void choiceStoresFactAndCodeWithoutRawTextAndUsesOptionInternally() {
        UUID id = UUID.randomUUID();
        var msg = SolutionMessage.choice(301L, 3, SolutionFact.INSTALLED_APP, FactValue.UNKNOWN, id, Instant.now());
        assertEquals(InputKind.OPTION, msg.getInputKind());
        assertEquals("installedApp", msg.getFactKey());
        assertEquals("UNKNOWN", msg.getSelectedCode());
        assertEquals(id, msg.getClientRequestId());
        assertTrue(msg.getSafeContent().contains("UNKNOWN"));
    }
}
