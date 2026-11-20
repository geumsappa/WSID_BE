package com.wsid.training.service;
import static org.junit.jupiter.api.Assertions.*;
import com.wsid.common.error.ApiException;
import org.junit.jupiter.api.Test;
class TrainingGeneratedTextTest {
    @Test void adviceAboutPasswordsIsNotAnActualPassword() {
        assertDoesNotThrow(() -> TrainingWorkflow.safeGenerated("비밀번호는 다른 사람에게 알려주지 마세요.", 1000));
        assertDoesNotThrow(() -> TrainingWorkflow.safeGenerated("인증번호는 공식 채널에서만 사용하세요.", 1000));
    }
    @Test void actualValuesAndLinksRemainBlocked() {
        for (String text : new String[]{"비밀번호: TestPass123!", "010-1234-5678", "4111111111111111", "https://example.org"})
            assertThrows(ApiException.class, () -> TrainingWorkflow.safeGenerated(text, 1000));
    }
}
