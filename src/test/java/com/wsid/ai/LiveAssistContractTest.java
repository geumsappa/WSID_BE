package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 로컬 가짜 Python 서버를 직접 호출하는 선택적 계약 테스트. 운영 승인 근거 검증이 아니다. */
@Tag("live-ai")
@EnabledIfEnvironmentVariable(named = "LIVE_AI_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LIVE_AI_KEY", matches = ".+")
class LiveAssistContractTest {
    @Test
    void planFollowupAndAnswerUseTheSharedHttpContract() {
        var properties = new AiProperties("http", System.getenv("LIVE_AI_URL"), System.getenv("LIVE_AI_KEY"),
                Duration.ofSeconds(2), Duration.ofSeconds(20));
        var client = new HttpAiClient(properties);
        var facts = Map.of("transferredMoney", "YES", "sharedInformation", "NO",
                "installedApp", "UNKNOWN", "contactContinues", "UNKNOWN");
        var first = client.planSolutionAssist(new SolutionAssistPlanRequest(
                "앱 관련해서 어떻게 해야 하나요?", null, facts, List.of(), 3, UUID.randomUUID()));
        assertEquals(SolutionAssistStatus.NEED_USER_INPUT, first.status());
        assertEquals("installedApp", first.clarification().factKey());
        var updated = Map.of("transferredMoney", "YES", "sharedInformation", "NO",
                "installedApp", "YES", "contactContinues", "UNKNOWN");
        var followup = client.planSolutionAssist(new SolutionAssistPlanRequest(
                null, first.questionIntent(), updated, List.of("installedApp"), 2, UUID.randomUUID()));
        assertEquals(SolutionAssistStatus.EVIDENCE_READY, followup.status());
        assertFalse(followup.candidateReferences().isEmpty());
        String code = followup.candidateReferences().getFirst().code();
        // 임의의 테스트 문단이다. 후보를 운영 승인 자료로 취급하지 않는다.
        var reference = new VerifiedReference(code, "계약 테스트용 제목", "계약 테스트를 위한 임시 문단입니다.");
        var answer = client.answerSolutionAssist(new SolutionAssistAnswerRequest(
                null, first.questionIntent(), updated, List.of(reference), UUID.randomUUID()));
        assertEquals(SolutionAssistStatus.ANSWERED, answer.status());
        assertFalse(answer.referenceCodes().isEmpty());
        assertTrue(List.of(code).containsAll(answer.referenceCodes()));
    }
}
