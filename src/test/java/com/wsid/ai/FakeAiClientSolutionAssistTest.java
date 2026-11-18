package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.SolutionAssistAnswerRequest;
import com.wsid.ai.dto.SolutionAssistIntent;
import com.wsid.ai.dto.SolutionAssistPlanRequest;
import com.wsid.ai.dto.SolutionAssistReason;
import com.wsid.ai.dto.SolutionAssistStatus;
import com.wsid.ai.dto.VerifiedReference;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeAiClientSolutionAssistTest {
    private final FakeAiClient client = new FakeAiClient();

    private static Map<String, String> facts(String money, String info, String app, String contact) {
        return Map.of("transferredMoney", money, "sharedInformation", info, "installedApp", app,
                "contactContinues", contact);
    }

    private static SolutionAssistPlanRequest plan(String question, SolutionAssistIntent intent,
            Map<String, String> facts, List<String> asked, int remaining) {
        return new SolutionAssistPlanRequest(question, intent, facts, asked, remaining, UUID.randomUUID());
    }

    @Test
    void asksFirstUnknownNotYetAskedFactWithGeneralIntent() {
        var result = client.planSolutionAssist(plan("어떻게 하죠?", null, facts("YES", "UNKNOWN", "UNKNOWN", "NO"),
                List.of(), 3));
        assertEquals(SolutionAssistStatus.NEED_USER_INPUT, result.status());
        assertEquals(SolutionAssistIntent.GENERAL, result.questionIntent());
        assertEquals("sharedInformation", result.clarification().factKey());

        var next = client.planSolutionAssist(plan(null, SolutionAssistIntent.GENERAL,
                facts("YES", "UNKNOWN", "UNKNOWN", "NO"), List.of("sharedInformation"), 2));
        assertEquals("installedApp", next.clarification().factKey());
    }

    @Test
    void returnsEvidenceWhenNothingToAskOrLimitReached() {
        var allKnown = client.planSolutionAssist(plan("질문", null, facts("YES", "NO", "NO", "NO"), List.of(), 3));
        assertEquals(SolutionAssistStatus.EVIDENCE_READY, allKnown.status());
        assertEquals(FakeAiClient.FAKE_ASSIST_REFERENCE, allKnown.candidateReferences().getFirst().code());

        var limit = client.planSolutionAssist(plan(null, SolutionAssistIntent.DEVICE_SAFETY,
                facts("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN"), List.of("installedApp"), 0));
        assertEquals(SolutionAssistStatus.EVIDENCE_READY, limit.status());
        assertEquals(SolutionAssistIntent.DEVICE_SAFETY, limit.questionIntent());
    }

    @Test
    void markersSelectOutOfScopeAndFailure() {
        var all = facts("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN");
        var out = client.planSolutionAssist(plan("[OUT_OF_SCOPE] 날씨", null, all, List.of(), 3));
        assertEquals(SolutionAssistReason.OUT_OF_SCOPE, out.reasonCode());
        assertThrows(AiUnavailableException.class,
                () -> client.planSolutionAssist(plan("[AI_FAIL]", null, all, List.of(), 3)));
    }

    @Test
    void answersWithFirstApprovedReferenceOrFails() {
        var refs = List.of(new VerifiedReference("REF_A", "제목", "문단"), new VerifiedReference("REF_B", "제목", "문단"));
        var result = client.answerSolutionAssist(new SolutionAssistAnswerRequest(null, SolutionAssistIntent.GENERAL,
                facts("YES", "NO", "NO", "NO"), refs, UUID.randomUUID()));
        assertEquals(SolutionAssistStatus.ANSWERED, result.status());
        assertEquals(List.of("REF_A"), result.referenceCodes());
        assertThrows(AiUnavailableException.class, () -> client.answerSolutionAssist(new SolutionAssistAnswerRequest(
                null, SolutionAssistIntent.GENERAL, facts("YES", "NO", "NO", "NO"),
                List.of(new VerifiedReference("REF_A", "[AI_FAIL]", "문단")), UUID.randomUUID())));
    }
}
