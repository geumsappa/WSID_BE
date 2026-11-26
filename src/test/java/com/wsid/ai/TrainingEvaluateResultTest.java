package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.TrainingEvaluateResult;
import com.wsid.ai.dto.TrainingEvaluateResult.MockPageEvaluation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TrainingEvaluateResultTest {
    private final MockPageEvaluation page = new MockPageEvaluation(false, false, false);

    @Test
    void acceptsEmptyListsAndLengthBoundaries() {
        assertDoesNotThrow(() -> result("평가", List.of(), List.of(), List.of()));
        var items = Collections.nCopies(10, "x".repeat(500));
        assertDoesNotThrow(() -> result("x".repeat(1000), items, items, items));
    }

    static Stream<String> invalidFeedback() {
        return Stream.of(null, "", " \n ", "x".repeat(1001));
    }

    @ParameterizedTest
    @MethodSource("invalidFeedback")
    void rejectsInvalidOverallFeedback(String text) {
        assertThrows(IllegalArgumentException.class, () -> result(text, List.of(), List.of(), List.of()));
    }

    static Stream<List<String>> invalidItems() {
        return Stream.of(null, Arrays.asList((String) null), List.of(""), List.of(" \n "),
                List.of("x".repeat(501)), Collections.nCopies(11, "항목"));
    }

    @ParameterizedTest
    @MethodSource("invalidItems")
    void validatesEachStringList(List<String> items) {
        assertThrows(IllegalArgumentException.class, () -> result("평가", items, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> result("평가", List.of(), items, List.of()));
        assertThrows(IllegalArgumentException.class, () -> result("평가", List.of(), List.of(), items));
    }

    @Test
    void requiresMockPageObjectAndExplicitlyEmptyTurnFeedback() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingEvaluateResult("평가", List.of(), List.of(), List.of(), null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingEvaluateResult("평가", List.of(), List.of(), null, page, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingEvaluateResult("평가", List.of(), List.of(), List.of(Map.of("feedback", "내용")), page, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingEvaluateResult("평가", List.of(), List.of(), Arrays.asList((Object) null), page, List.of()));
    }

    @Test
    void copiesEveryListAndDoesNotPrintEvaluationText() {
        var strengths = new ArrayList<>(List.of("잘한 행동 원문"));
        var improvements = new ArrayList<>(List.of("개선할 점 원문"));
        var recommendations = new ArrayList<>(List.of("권고 원문"));
        var result = result("종합 평가 원문", strengths, improvements, recommendations);
        strengths.clear(); improvements.clear(); recommendations.clear();
        assertEquals(List.of("잘한 행동 원문"), result.strengths());
        assertEquals(List.of("개선할 점 원문"), result.improvements());
        assertEquals(List.of("권고 원문"), result.recommendations());
        for (List<?> list : List.of(result.strengths(), result.improvements(), result.recommendations(), result.turnFeedback())) {
            assertThrows(UnsupportedOperationException.class, () -> list.add(null));
        }
        for (String secret : List.of("잘한 행동 원문", "개선할 점 원문", "권고 원문", "종합 평가 원문")) {
            assertFalse(result.toString().contains(secret));
        }
    }

    private TrainingEvaluateResult result(String overall, List<String> strengths,
            List<String> improvements, List<String> recommendations) {
        return new TrainingEvaluateResult(overall, strengths, improvements, List.of(), page, recommendations);
    }
}
