package com.wsid.ai;

import com.wsid.ai.dto.SolutionExplainRequest;
import com.wsid.ai.dto.SolutionExplainResult;
import com.wsid.ai.dto.VerifiedReference;
import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 요청 근거 코드와 명시적인 금지 출력 패턴을 검사해요. 기관명·근거 충실성의 의미 검증은 아니에요. */
public final class SolutionExplanationValidator {
    /** 수량·금액·시간 단위. "112번"의 "번"은 신고 번호 안내일 수 있어 넣지 않아요. */
    private static final String NOT_UNIT =
            "(?!\\s*(?:개|건|명|원|만|천|억|일|월|년|시|분|초|회|장|쪽|가지|단계|%))";
    private static final List<Pattern> FORBIDDEN = List.of(
            // 명시적인 URI·일반 도메인·이메일. 실제 링크는 검토된 카드에서 제공해요.
            Pattern.compile("(?iu)\\b(?:[a-z][a-z0-9+.-]*://|(?:https?|ftp|file|tel|mailto|sms|data|javascript)\\s*:|www\\.)"),
            Pattern.compile("(?iu)\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}\\b"),
            Pattern.compile("(?iu)[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,63}"),
            // HTML 태그(< 바로 뒤 이름)·공백 없는 자동 링크·주석. "3 < 5 / 2 > 1" 같은 비교식은 제외해요.
            Pattern.compile("(?is)</?[a-z][a-z0-9-]*(?:\\s[^<>]*)?/?>|<[^<>\\s]*[@:/][^<>\\s]*>|<!--"),
            // Markdown 링크는 ](, ][ 처럼 붙어 있을 때만 봐요. "[주의] (중요)"는 일반 문장이에요.
            Pattern.compile("!?\\[[^\\]\\r\\n]*\\](?:\\(|\\[)|(?m)^\\s*\\[[^\\]\\r\\n]+\\]\\s*:"),
            // 흔한 전화 표기. 모든 국가·우회 표기를 판별하는 검사는 아니에요.
            // 숫자 바로 뒤에 수량·금액 단위가 오면 전화번호로 보지 않아요(예: 10 000원, 119개).
            Pattern.compile("(?<!\\d)0\\d{8,10}(?!\\d)"),
            Pattern.compile("(?<!\\d)(?:\\(?\\d{2,4}\\)?[-.\\s]+){1,2}\\d{3,4}(?!\\d)" + NOT_UNIT),
            Pattern.compile("(?<!\\d)1[568]\\d{2}[-.\\s]*\\d{4}(?!\\d)"),
            Pattern.compile("(?<!\\d)\\+\\d(?:[-.\\s]*\\d){7,14}(?!\\d)"),
            Pattern.compile("(?<!\\d)(?:112|119|118|182|1332)(?!\\d)" + NOT_UNIT));

    private SolutionExplanationValidator() {}

    public static SolutionExplainResult validate(SolutionExplainRequest request, SolutionExplainResult result) {
        if (request == null) {
            throw new IllegalArgumentException("대응 설명 요청이 필요해요.");
        }
        if (result == null) {
            throw new AiUnavailableException("AI-07 설명 결과가 없어요.");
        }
        var allowed = request.verifiedReferences().stream().map(VerifiedReference::code).collect(Collectors.toSet());
        if (!allowed.containsAll(result.referenceCodes())
                || (!allowed.isEmpty() && result.referenceCodes().isEmpty())) {
            throw new AiUnavailableException("AI-07 설명 근거가 요청과 일치하지 않아요.");
        }
        if (containsForbiddenOutput(result.explanation())) {
            throw new AiUnavailableException("AI-07 설명에 허용되지 않은 출력이 있어요.");
        }
        return result;
    }

    /** 링크·이메일·HTML·흔한 전화 표기를 찾아요. 검사할 때만 정규화하고 원문을 부분 삭제하거나 다시 쓰지 않아요. */
    static boolean containsForbiddenOutput(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\p{Cf}", "");
        return FORBIDDEN.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }
}
