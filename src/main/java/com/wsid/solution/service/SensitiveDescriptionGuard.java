package com.wsid.solution.service;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 피해 설명에 민감정보가 보이면 AI 전송·저장 전에 거부해요(가리고 진행하지 않음).
 * 초안 기준이며 모든 개인정보를 찾는다고 보장하지 않아요. 기준은 FastAPI 쪽 검사와 맞춰 확정해요.
 * <ul>
 *   <li>주민등록번호: 6자리-7자리(뒷자리 1~4로 시작)</li>
 *   <li>카드번호: 4자리 4묶음(공백·하이픈 허용)</li>
 *   <li>계좌·전화번호 등: 하이픈으로 이어진 숫자가 9자리 이상, 또는 붙어 있는 숫자 10자리 이상</li>
 *   <li>인증번호·비밀번호·OTP 뒤 4~8자리 숫자</li>
 * </ul>
 * 쉼표 금액(1,000,000원)·날짜(2026-11-17, 2026.11.17)·시각은 거부하지 않아요.
 */
@Component
public class SensitiveDescriptionGuard {
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?<![0-9])[0-9]{6}\\s*-\\s*[1-4][0-9]{6}(?![0-9])"),
            Pattern.compile("(?<![0-9])[0-9]{4}(?:[ -][0-9]{4}){3}(?![0-9])"),
            Pattern.compile("(?<![0-9])[0-9]{10,}(?![0-9])"),
            Pattern.compile("(?<![0-9])[0-9]{2,6}(?:-[0-9]{2,8}){1,3}(?![0-9])"),
            Pattern.compile("(?:인증\\s*번호|인증\\s*코드|비밀\\s*번호|비번|OTP|otp|보안\\s*카드)\\D{0,6}[0-9]{4,8}(?![0-9])"));

    public void check(String description) {
        if (description == null) return;
        for (Pattern pattern : PATTERNS) {
            var matcher = pattern.matcher(description);
            while (matcher.find()) {
                if (isSensitive(pattern, matcher.group())) throw new ApiException(ErrorCode.SENSITIVE_DATA_DETECTED);
            }
        }
    }

    private boolean isSensitive(Pattern pattern, String match) {
        if (pattern != PATTERNS.get(3)) return true;
        // 하이픈 숫자는 날짜(4-2-2 = 8자리)를 넘는 9자리 이상만 계좌·전화번호로 봐요.
        return match.replace("-", "").length() >= 9;
    }
}
