package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.ScenarioGenerateRequest;
import com.wsid.training.domain.ContextFacts;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ScenarioGenerateRequestTest {

    @Test
    void normalizesAndCopiesFactsWithoutDependingOnTrainingTypes() {
        Map<String, String> original = new HashMap<>(Map.of("activity", "  중고거래  ", "role", " 판매자 "));
        var request = request(original);
        original.put("role", "구매자");
        assertEquals(Map.of("activity", "중고거래", "role", "판매자"), request.facts());
        assertThrows(UnsupportedOperationException.class, () -> request.facts().put("item", "노트북"));
    }

    @Test
    void acceptsActivityOnlyAndLengthBoundary() {
        assertDoesNotThrow(() -> request(Map.of("activity", "x".repeat(100))));
    }

    @Test
    void allowedKeysMatchTrainingContract() {
        assertEquals(ContextFacts.ALLOWED, ScenarioGenerateRequest.ALLOWED_KEYS);
    }

    static Stream<Map<String, String>> invalidFacts() {
        var nullKey = new HashMap<String, String>();
        nullKey.put(null, "값");
        var nullValue = new HashMap<String, String>();
        nullValue.put("activity", null);
        return Stream.of(null, Map.of(), Map.of("role", "판매자"),
                Map.of("activity", "중고거래", "unknown", "값"), nullKey, nullValue,
                Map.of("activity", " "), Map.of("activity", "x".repeat(101)));
    }

    @ParameterizedTest
    @MethodSource("invalidFacts")
    void rejectsInvalidFactsInsteadOfSilentlyDroppingThem(Map<String, String> facts) {
        assertThrows(IllegalArgumentException.class, () -> request(facts));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "UNKNOWN", "USED_GOODS_TRADE", "secondhand_trade"})
    void rejectsUnsupportedFraudType(String code) {
        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioGenerateRequest(Map.of("activity", "중고거래"), code, UUID.randomUUID()));
    }

    @Test
    void requiresRequestIdAndDoesNotPrintFactsInExceptionOrToString() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioGenerateRequest(Map.of("activity", "중고거래"), "SECONDHAND_TRADE", null));
        String secret = "사용자만의-개인-내용";
        assertFalse(request(Map.of("activity", secret)).toString().contains(secret));
        var error = assertThrows(IllegalArgumentException.class,
                () -> request(Map.of("activity", "중고거래", "unknown", secret)));
        assertFalse(error.getMessage().contains(secret));
    }

    private ScenarioGenerateRequest request(Map<String, String> facts) {
        return new ScenarioGenerateRequest(facts, "SECONDHAND_TRADE", UUID.randomUUID());
    }
}
