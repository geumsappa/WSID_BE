package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.SolutionExplainRequest;
import com.wsid.ai.dto.VerifiedReference;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeAiClientSolutionExplainTest {
    private final FakeAiClient client = new FakeAiClient();

    @Test
    void deterministicResultUsesOnlyFirstRequestCodeWithoutEchoingFactsOrReferenceText() {
        var request = request(List.of(new VerifiedReference("REF_2", "비공개 제목", "비공개 근거 문단"),
                new VerifiedReference("REF_1", "다른 제목", "다른 문단")));
        var result = client.explainSolution(request);
        assertEquals(result, client.explainSolution(request));
        assertEquals(List.of("REF_2"), result.referenceCodes());
        assertSame(result, SolutionExplanationValidator.validate(request, result));
        assertFalse(result.explanation().contains("비공개"));
        assertFalse(result.explanation().contains("transferredMoney"));
        assertFalse(result.explanation().contains("YES"));
    }

    @Test
    void emptyReferencesReturnFixedExplanationAndExplicitEmptyCodes() {
        var result = client.explainSolution(request(List.of()));
        assertEquals("확인된 근거가 없어 추가 설명을 제공하지 않습니다.", result.explanation());
        assertEquals(List.of(), result.referenceCodes());
    }

    @Test
    void failureMarkerInAnyReferenceTitleFailsWithoutKeepingPrivateContent() {
        var request = request(List.of(new VerifiedReference("REF_1", "일반 제목", "문단"),
                new VerifiedReference("REF_2", "비공개 [AI_FAIL] 제목", "비공개 문단")));
        var error = assertThrows(AiUnavailableException.class, () -> client.explainSolution(request));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("비공개"));
        assertFalse(error.getMessage().contains("[AI_FAIL]"));
    }

    @Test
    void excerptMarkerDoesNotTriggerTitleOnlyFailureAndIsNeverEchoed() {
        var result = client.explainSolution(request(List.of(new VerifiedReference("REF_1", "일반 제목", "[AI_FAIL] 문단"))));
        assertFalse(result.explanation().contains("[AI_FAIL]"));
    }

    @Test
    void nullRequestIsInputError() {
        assertThrows(IllegalArgumentException.class, () -> client.explainSolution(null));
    }

    private SolutionExplainRequest request(List<VerifiedReference> references) {
        return new SolutionExplainRequest(Map.of("transferredMoney", "YES"), references, UUID.randomUUID());
    }
}
