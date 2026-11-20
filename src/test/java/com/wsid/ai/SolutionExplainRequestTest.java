package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.SolutionExplainRequest;
import com.wsid.ai.dto.SolutionExtractRequest;
import com.wsid.ai.dto.VerifiedReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SolutionExplainRequestTest {
    @Test
    void acceptsPartialFactsEmptyReferencesAndUnknownValues() {
        assertDoesNotThrow(() -> request(Map.of("transferredMoney", "YES"), List.of()));
        var facts = new HashMap<String, String>();
        SolutionExtractRequest.FACT_KEYS.forEach(key -> facts.put(key, "UNKNOWN"));
        assertDoesNotThrow(() -> request(facts, references(10)));
    }

    static Stream<Map<String, String>> invalidFacts() {
        var nullKey = new HashMap<String, String>(); nullKey.put(null, "YES");
        var nullValue = new HashMap<String, String>(); nullValue.put("transferredMoney", null);
        return Stream.of(null, Map.of(), nullKey, nullValue, Map.of("unknown", "YES"),
                Map.of("transferredMoney", "yes"), Map.of("transferredMoney", " YES "),
                Map.of("transferredMoney", "MAYBE"));
    }

    @ParameterizedTest
    @MethodSource("invalidFacts")
    void rejectsInvalidFactsWithoutSilentlyDroppingThem(Map<String, String> facts) {
        assertThrows(IllegalArgumentException.class, () -> request(facts, List.of()));
    }

    @Test
    void rejectsNullReferencesNullElementDuplicateCodeOverLimitAndMissingId() {
        assertThrows(IllegalArgumentException.class, () -> request(Map.of("installedApp", "NO"), null));
        assertThrows(IllegalArgumentException.class, () -> request(Map.of("installedApp", "NO"), Arrays.asList((VerifiedReference) null)));
        assertThrows(IllegalArgumentException.class, () -> request(Map.of("installedApp", "NO"),
                List.of(reference("REF_1"), new VerifiedReference("REF_1", "다른 제목", "다른 문단"))));
        assertThrows(IllegalArgumentException.class, () -> request(Map.of("installedApp", "NO"), references(11)));
        assertThrows(IllegalArgumentException.class, () -> new SolutionExplainRequest(Map.of("installedApp", "NO"), List.of(), null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"lowercase", " REF_1", "REF-1", "REF.1", "REF_가", "REF/1"})
    void rejectsInvalidReferenceCode(String code) {
        assertThrows(IllegalArgumentException.class, () -> reference(code));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \n\t "})
    void rejectsMissingTitleOrExcerpt(String text) {
        assertThrows(IllegalArgumentException.class, () -> new VerifiedReference("REF_1", text, "문단"));
        assertThrows(IllegalArgumentException.class, () -> new VerifiedReference("REF_1", "제목", text));
    }

    @Test
    void acceptsExactBoundariesAndRejectsOversizedReferenceFields() {
        assertDoesNotThrow(() -> new VerifiedReference("A".repeat(100), "t".repeat(200), "x".repeat(2000)));
        assertThrows(IllegalArgumentException.class, () -> reference("A".repeat(101)));
        assertThrows(IllegalArgumentException.class, () -> new VerifiedReference("REF_1", "t".repeat(201), "문단"));
        assertThrows(IllegalArgumentException.class, () -> new VerifiedReference("REF_1", "제목", "x".repeat(2001)));
        var reference = new VerifiedReference("REF_1", "  제목  ", "  문단  ");
        assertEquals("  제목  ", reference.title());
        assertEquals("  문단  ", reference.excerpt());
    }

    @Test
    void copiesCollectionsAndDoesNotPrintFactsTitlesOrExcerpts() {
        var facts = new HashMap<>(Map.of("transferredMoney", "YES"));
        var reference = new VerifiedReference("REF_PRIVATE", "비공개 제목", "비공개 근거 원문");
        var refs = new ArrayList<>(List.of(reference));
        var request = request(facts, refs);
        facts.clear(); refs.clear();
        assertEquals("YES", request.facts().get("transferredMoney"));
        assertEquals(List.of(reference), request.verifiedReferences());
        assertThrows(UnsupportedOperationException.class, () -> request.facts().clear());
        assertThrows(UnsupportedOperationException.class, () -> request.verifiedReferences().clear());
        for (String privateText : List.of("transferredMoney", "YES", "REF_PRIVATE", "비공개 제목", "비공개 근거 원문")) {
            assertFalse(request.toString().contains(privateText));
            assertFalse(reference.toString().contains(privateText));
        }
    }

    private SolutionExplainRequest request(Map<String, String> facts, List<VerifiedReference> refs) {
        return new SolutionExplainRequest(facts, refs, UUID.randomUUID());
    }

    private static VerifiedReference reference(String code) {
        return new VerifiedReference(code, "제목", "승인 문단");
    }

    private static List<VerifiedReference> references(int count) {
        return IntStream.range(0, count).mapToObj(i -> reference("REF_" + i)).toList();
    }
}
