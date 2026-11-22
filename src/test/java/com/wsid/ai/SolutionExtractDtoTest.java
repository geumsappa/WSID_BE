package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.SolutionExtractRequest;
import com.wsid.ai.dto.SolutionExtractResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SolutionExtractDtoTest {

    @Test
    void acceptsEmptyAndPartialKnownFactsAndPreservesDescription() {
        assertDoesNotThrow(() -> request("x".repeat(1000), Map.of()));
        var request = request("  알 수 없어요.  ", Map.of("transferredMoney", "YES"));
        assertEquals("  알 수 없어요.  ", request.description());
        assertEquals(Map.of("transferredMoney", "YES"), request.knownFacts());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \n\t "})
    void rejectsMissingDescription(String description) {
        assertThrows(IllegalArgumentException.class, () -> request(description, Map.of()));
    }

    @Test
    void rejectsOversizedDescriptionMissingMapAndId() {
        assertThrows(IllegalArgumentException.class, () -> request("x".repeat(1001), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> request("설명", null));
        assertThrows(IllegalArgumentException.class, () -> new SolutionExtractRequest("설명", Map.of(), null));
    }

    static Stream<Map<String, String>> invalidKnownFacts() {
        var nullKey = new HashMap<String, String>(); nullKey.put(null, "YES");
        var nullValue = new HashMap<String, String>(); nullValue.put("transferredMoney", null);
        return Stream.of(nullKey, nullValue, Map.of("unknown", "YES"), Map.of("transferredMoney", "MAYBE"),
                Map.of("transferredMoney", "yes"), Map.of("transferredMoney", " YES "), Map.of("transferredMoney", ""));
    }

    @ParameterizedTest
    @MethodSource("invalidKnownFacts")
    void rejectsInvalidKnownFactsInsteadOfDroppingThem(Map<String, String> facts) {
        assertThrows(IllegalArgumentException.class, () -> request("설명", facts));
    }

    @Test
    void requestCopiesFactsAndNeverPrintsDescriptionOrValues() {
        var source = new HashMap<>(Map.of("transferredMoney", "YES"));
        var request = request("사용자 비공개 설명 원문", source);
        source.put("transferredMoney", "NO");
        assertEquals("YES", request.knownFacts().get("transferredMoney"));
        assertThrows(UnsupportedOperationException.class, () -> request.knownFacts().clear());
        assertFalse(request.toString().contains("사용자 비공개 설명 원문"));
        assertFalse(request.toString().contains("transferredMoney"));
        assertFalse(request.toString().contains("YES"));
    }

    @Test
    void acceptsUnknownOrFullyKnownFactsAndUncertaintyOrderIsNotSignificant() {
        var facts = unknown();
        assertDoesNotThrow(() -> new SolutionExtractResult(facts,
                List.of("installedApp", "sharedInformation", "contactContinues", "transferredMoney")));
        facts.replaceAll((key, value) -> "NO");
        assertDoesNotThrow(() -> new SolutionExtractResult(facts, List.of()));
    }

    static Stream<Map<String, String>> invalidResults() {
        var missing = unknown(); missing.remove("installedApp");
        var extra = unknown(); extra.put("rawDescription", "UNKNOWN");
        var wrongValue = unknown(); wrongValue.put("installedApp", "MAYBE");
        var nullValue = unknown(); nullValue.put("installedApp", null);
        return Stream.of(null, Map.of(), missing, extra, wrongValue, nullValue);
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void rejectsMissingExtraOrInvalidResultFacts(Map<String, String> facts) {
        assertThrows(IllegalArgumentException.class,
                () -> new SolutionExtractResult(facts, List.of("transferredMoney", "contactContinues", "sharedInformation", "installedApp")));
    }

    static Stream<List<String>> invalidUncertainty() {
        return Stream.of(null, List.of(), Arrays.asList((String) null), List.of("unknown"),
                List.of("transferredMoney"), List.of("transferredMoney", "transferredMoney", "sharedInformation", "installedApp"),
                List.of("transferredMoney", "contactContinues", "sharedInformation", "installedApp", "installedApp"));
    }

    @ParameterizedTest
    @MethodSource("invalidUncertainty")
    void rejectsMissingDuplicateOrMismatchingUncertainty(List<String> uncertain) {
        assertThrows(IllegalArgumentException.class, () -> new SolutionExtractResult(unknown(), uncertain));
    }

    @Test
    void knownFactMustNotAppearInUncertainFields() {
        var facts = unknown(); facts.put("transferredMoney", "YES");
        assertThrows(IllegalArgumentException.class,
                () -> new SolutionExtractResult(facts, List.of("transferredMoney", "contactContinues", "sharedInformation", "installedApp")));
    }

    @Test
    void resultCopiesBothCollectionsAndDoesNotPrintFactValues() {
        var facts = unknown();
        var fields = new ArrayList<>(List.of("transferredMoney", "contactContinues", "sharedInformation", "installedApp"));
        var result = new SolutionExtractResult(facts, fields);
        facts.clear(); fields.clear();
        assertEquals(4, result.facts().size());
        assertEquals(4, result.uncertainFields().size());
        assertThrows(UnsupportedOperationException.class, () -> result.facts().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.uncertainFields().clear());
        assertFalse(result.toString().contains("UNKNOWN"));
        assertFalse(result.toString().contains("transferredMoney"));
    }

    private SolutionExtractRequest request(String description, Map<String, String> facts) {
        return new SolutionExtractRequest(description, facts, UUID.randomUUID());
    }

    private static Map<String, String> unknown() {
        return new HashMap<>(Map.of("transferredMoney", "UNKNOWN", "contactContinues", "UNKNOWN",
                "sharedInformation", "UNKNOWN", "installedApp", "UNKNOWN"));
    }

    @org.junit.jupiter.api.Test
    void fraudTypeKeepsOnlyKnownCodes() {
        var facts = java.util.Map.of("transferredMoney", "YES", "sharedInformation", "NO", "installedApp", "NO", "contactContinues", "NO");
        org.junit.jupiter.api.Assertions.assertEquals("SMISHING", new SolutionExtractResult(facts, java.util.List.of(), "SMISHING").fraudType());
        org.junit.jupiter.api.Assertions.assertNull(new SolutionExtractResult(facts, java.util.List.of(), "ROMANCE_SCAM").fraudType());
        org.junit.jupiter.api.Assertions.assertNull(new SolutionExtractResult(facts, java.util.List.of()).fraudType());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new com.wsid.ai.dto.SolutionAssistPlanRequest(
                "질문", null, facts, java.util.List.of(), 3, java.util.UUID.randomUUID(), "ROMANCE_SCAM"));
    }
}
