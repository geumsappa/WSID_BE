package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.SolutionExplainRequest;
import com.wsid.ai.dto.SolutionExplainResult;
import com.wsid.ai.dto.VerifiedReference;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SolutionExplanationValidatorTest {
    @Test
    void acceptsSubsetOrAllReferencesAndPreservesResult() {
        var request = request(List.of(reference("REF_1"), reference("REF_2")));
        var subset = result("설명", List.of("REF_2"));
        assertSame(subset, SolutionExplanationValidator.validate(request, subset));
        assertNotNull(SolutionExplanationValidator.validate(request, result("설명", List.of("REF_2", "REF_1"))));
    }

    @Test
    void requiresCitationWhenReferencesExistAndRejectsAnyUnknownCode() {
        var request = request(List.of(reference("REF_1")));
        for (List<String> codes : List.of(List.<String>of(), List.of("REF_NEW"), List.of("REF_1", "REF_NEW"))) {
            var error = assertThrows(AiUnavailableException.class,
                    () -> SolutionExplanationValidator.validate(request, result("비공개 설명", codes)));
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("비공개"));
        }
    }

    @Test
    void emptyReferencesRequireEmptyCodesButDoNotEstablishGrounding() {
        var request = request(List.of());
        assertNotNull(SolutionExplanationValidator.validate(request, result("은행과 공식 신고 채널을 확인하세요.", List.of())));
        assertThrows(AiUnavailableException.class,
                () -> SolutionExplanationValidator.validate(request, result("설명", List.of("REF_1"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.invalid", "HTTP://EXAMPLE.INVALID", "http ://example.invalid", "ftp://host/path",
            "www.example.invalid", "example.invalid", "user@example.invalid", "tel:01000000000", "mailto:user@example.invalid",
            "javascript:alert(1)", "data:text/plain,value", "[링크](경로)", "![그림](경로)", "[링크][출처]",
            "[출처]: /path", "<a href='/path'>확인</a>", "<p>내용</p>", "<https://example.invalid>", "<!-- 숨김 -->",
            "010-0000-0000", "01000000000", "02 000 0000", "(02) 000-0000", "1500-0000", "15000000",
            "+82 10 0000 0000", "+1 555 000 0000", "112", "119", "1332",
            "112 신고 여부를 확인하세요.", "112번으로 신고하세요.", "[1단계][2단계]", "<a@b.c>를 누르세요.",
            "<a\nhref='/path'>확인</a>",
            "ｈｔｔｐｓ：／／ｅｘａｍｐｌｅ．ｉｎｖａｌｉｄ", "０１０－００００－００００", "ht\u200btps://example.invalid"
    })
    void rejectsExplicitContactLinkAndMarkupPatterns(String explanation) {
        var error = assertThrows(AiUnavailableException.class,
                () -> SolutionExplanationValidator.validate(request(List.of(reference("REF_1"))), result(explanation, List.of("REF_1"))));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains(explanation));
    }

    @ParameterizedTest
    @ValueSource(strings = {"은행·경찰·공식 신고 채널을 확인하세요.", "1단계와 2단계를 확인하세요.", "2026-11-16 기준입니다.",
            "준비 항목 3개를 확인해 주세요.", "금액은 1,000원입니다.", "비교식 1 < 2를 확인하세요.", "  검토된 설명  ",
            // 회귀: 이전 정규식이 링크·전화번호·HTML로 잘못 판단했던 정상 문장이에요.
            "[주의] (중요) 은행에 먼저 확인해 주세요.", "[1단계] [2단계] 순서로 진행해 주세요.",
            "준비 서류 119개를 모두 낼 필요는 없어요.", "피해 금액 10 000원도 기록해 두세요.",
            "3 < 5 / 2 > 1 같은 비교는 필요 없어요.", "2026. 11. 16. 기준 안내예요.", "약 30분 안에 확인하세요."})
    void acceptsOrdinaryNumbersPunctuationAndGenericInstitutionCategories(String explanation) {
        var result = result(explanation, List.of("REF_1"));
        assertEquals(explanation, SolutionExplanationValidator.validate(request(List.of(reference("REF_1"))), result).explanation());
    }

    @Test
    void nullRequestIsInputErrorAndNullResultIsUnavailable() {
        assertThrows(IllegalArgumentException.class, () -> SolutionExplanationValidator.validate(null, result("설명", List.of())));
        assertThrows(AiUnavailableException.class, () -> SolutionExplanationValidator.validate(request(List.of()), null));
    }

    private SolutionExplainRequest request(List<VerifiedReference> refs) {
        return new SolutionExplainRequest(Map.of("transferredMoney", "YES"), refs, UUID.randomUUID());
    }

    private VerifiedReference reference(String code) { return new VerifiedReference(code, "제목", "승인 설명용 문단"); }
    private SolutionExplainResult result(String explanation, List<String> codes) { return new SolutionExplainResult(explanation, codes); }
}
