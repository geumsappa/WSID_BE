package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.SolutionExplainResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SolutionExplainResultTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \n\t "})
    void rejectsMissingExplanation(String explanation) {
        assertThrows(IllegalArgumentException.class, () -> new SolutionExplainResult(explanation, List.of()));
    }

    @Test
    void acceptsExactLimitsEmptyCodesAndPreservesExplanation() {
        assertDoesNotThrow(() -> new SolutionExplainResult("x".repeat(2000),
                IntStream.range(0, 10).mapToObj(i -> "REF_" + i).toList()));
        assertEquals("  설명  ", new SolutionExplainResult("  설명  ", List.of()).explanation());
        assertThrows(IllegalArgumentException.class, () -> new SolutionExplainResult("x".repeat(2001), List.of()));
    }

    static Stream<List<String>> invalidCodes() {
        return Stream.of(null, Arrays.asList((String) null), List.of(""), List.of("ref_1"), List.of(" REF_1 "),
                List.of("REF-1"), List.of("A".repeat(101)), List.of("REF_1", "REF_1"),
                IntStream.range(0, 11).mapToObj(i -> "REF_" + i).toList());
    }

    @ParameterizedTest
    @MethodSource("invalidCodes")
    void rejectsInvalidMissingDuplicateOrTooManyCodes(List<String> codes) {
        assertThrows(IllegalArgumentException.class, () -> new SolutionExplainResult("설명", codes));
    }

    @Test
    void copiesCodesAndNeverPrintsExplanationOrCodes() {
        var source = new ArrayList<>(List.of("REF_PRIVATE"));
        var result = new SolutionExplainResult("비공개 생성 설명", source);
        source.clear();
        assertEquals(List.of("REF_PRIVATE"), result.referenceCodes());
        assertThrows(UnsupportedOperationException.class, () -> result.referenceCodes().clear());
        assertFalse(result.toString().contains("비공개 생성 설명"));
        assertFalse(result.toString().contains("REF_PRIVATE"));
    }
}
