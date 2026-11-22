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

/** v1.2 조건 계약을 실제 Python 서버와 왕복 확인하는 선택적 테스트(LIVE_AI_URL·LIVE_AI_KEY가 있을 때만). */
@Tag("live-ai")
@EnabledIfEnvironmentVariable(named = "LIVE_AI_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LIVE_AI_KEY", matches = ".+")
class LiveAssistConditionContractTest {
    static final String CODE = "REF_LAW_REFUND_PROCEDURE_V1_P2";
    static final String QUOTE = "제외: 물건·서비스를 주겠다며 속인 사기(중고거래, 온라인 쇼핑, 게임 아이템, 사이버 경매)";
    static final Map<String, String> FACTS = Map.of("transferredMoney", "YES", "sharedInformation", "UNKNOWN",
            "installedApp", "UNKNOWN", "contactContinues", "UNKNOWN");
    final HttpAiClient client = new HttpAiClient(new AiProperties("http", System.getenv("LIVE_AI_URL"),
            System.getenv("LIVE_AI_KEY"), Duration.ofSeconds(2), Duration.ofSeconds(30)));

    @Test
    void pythonAcceptsSpringConditionHashAndShape() {
        // Spring이 계산한 conditionId를 Python이 같은 해시로 확인해야 422 없이 처리돼요.
        var done = List.of(SolutionAssistConfirmedCondition.of(SolutionAssistCondition.of(CODE, QUOTE), "NO"));
        var plan = client.planSolutionAssist(new SolutionAssistPlanRequest("돈을 보냈는데 돌려받을 수 있나요?", null, FACTS,
                List.of(), 2, UUID.randomUUID(), null, null, done));
        assertNotNull(plan.status());
        if (plan.clarification() != null) {
            // v1.2에서는 네 사실을 다시 묻지 않고, 묻는다면 이미 답한 조건이 아닌 조건이에요.
            assertTrue(plan.clarification().isCondition());
            assertNotEquals(done.getFirst().conditionId(), plan.clarification().condition().conditionId());
        }
        var answer = client.answerSolutionAssist(new SolutionAssistAnswerRequest("돈을 보냈는데 돌려받을 수 있나요?",
                SolutionAssistIntent.TRANSFER_RESPONSE, FACTS,
                List.of(new VerifiedReference(CODE, "통신사기피해환급법 적용 대상", "적용 대상: 전기통신으로 속여 송금하게 한 경우. " + QUOTE)),
                UUID.randomUUID(), null, null, done));
        assertNotNull(answer.status());
    }

    @Test
    void firstV12RequestWithEmptyListIsAccepted() {
        var plan = client.planSolutionAssist(new SolutionAssistPlanRequest("돈을 보냈는데 돌려받을 수 있나요?", null, FACTS,
                List.of(), 3, UUID.randomUUID(), null, null, List.of()));
        assertNotNull(plan.status());
        if (plan.clarification() != null) assertTrue(plan.clarification().isCondition());
    }
}
