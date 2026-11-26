package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.dto.StartSolutionRequest;
import com.wsid.solution.service.SensitiveDescriptionGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class StartSolutionRequestTest {
    private final ObjectMapper json = new ObjectMapper();
    private final SensitiveDescriptionGuard guard = new SensitiveDescriptionGuard();

    private StartSolutionRequest parse(String body) {
        return StartSolutionRequest.from(json.readTree(body));
    }

    @Test
    void missingNullEmptyAndBlankDescriptionsStartWithChoices() {
        assertTrue(StartSolutionRequest.from(null).description().isEmpty());
        for (String body : new String[] {"{}", "{\"description\":null}", "{\"description\":\"\"}", "{\"description\":\" \\n\\t \"}"}) {
            assertTrue(parse(body).description().isEmpty(), body);
        }
    }

    @Test
    void keepsDescriptionTextAsIs() {
        assertEquals(" 송금 후 연락이 끊겼어요. ", parse("{\"description\":\" 송금 후 연락이 끊겼어요. \"}").description().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"text\"", "1", "true", "{\"description\":1}", "{\"description\":true}",
            "{\"description\":[]}", "{\"description\":{}}", "{\"userId\":1}", "{\"description\":\"a\",\"anonymousOwnerId\":\"x\"}",
            "{\"facts\":{}}", "{\"stateVersion\":0}"})
    void rejectsOtherShapesTypesAndServerOwnedKeys(String body) {
        var error = assertThrows(ApiException.class, () -> parse(body));
        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode());
    }

    @Test
    void lengthIsCheckedOnRawStringBeforeStripping() {
        assertEquals(1_000, parse("{\"description\":\"" + "가".repeat(1_000) + "\"}").description().orElseThrow().length());
        assertThrows(ApiException.class, () -> parse("{\"description\":\"" + "가".repeat(1_001) + "\"}"));
        assertThrows(ApiException.class, () -> parse("{\"description\":\"" + " ".repeat(1_001) + "\"}"));
    }

    @Test
    void toStringNeverContainsDescription() {
        var request = parse("{\"description\":\"비밀스러운 피해 설명\"}");
        assertFalse(request.toString().contains("비밀스러운"));
        assertTrue(request.toString().contains("descriptionLength=11"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "제 주민번호는 900101-1234567 이에요", "주민번호 9001011234567", "카드 1234 5678 9012 3456 로 결제",
            "카드 1234-5678-9012-3456", "계좌 110-123-456789 로 보냈어요", "010-1234-5678 번호로 연락이 와요",
            "01012345678로 문자", "02-123-4567 으로 전화", "인증번호 123456 을 알려줬어요", "OTP 987654 불러줬어요",
            "비밀번호는 1234 예요", "인증 코드: 4821"})
    void guardRejectsSensitiveNumbers(String text) {
        var error = assertThrows(ApiException.class, () -> guard.check(text));
        assertEquals(ErrorCode.SENSITIVE_DATA_DETECTED, error.errorCode());
        assertFalse(error.getMessage().contains(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "모르는 사람에게 돈을 보냈고 연락이 끊겼어요.", "1,000,000원을 보냈어요", "50만원 송금", "300000원을 이체했어요",
            "2026-11-17 오후 3시에 보냈어요", "2026.11.17 10:30 에 연락", "인증번호를 알려줬어요", "앱을 2개 설치했어요",
            "3일 전부터 연락이 없어요"})
    void guardAllowsOrdinaryAmountsDatesAndWords(String text) {
        assertDoesNotThrow(() -> guard.check(text));
    }
}
