package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.SolutionAssistAnswerRequest;
import com.wsid.ai.dto.SolutionAssistAnswerResult;
import com.wsid.ai.dto.SolutionAssistClarification;
import com.wsid.ai.dto.SolutionAssistIntent;
import com.wsid.ai.dto.SolutionAssistPlanRequest;
import com.wsid.ai.dto.SolutionAssistPlanResult;
import com.wsid.ai.dto.SolutionAssistReason;
import com.wsid.ai.dto.SolutionAssistStatus;
import com.wsid.ai.dto.VerifiedReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class SolutionAssistDtoTest {
    private static final Map<String, String> FACTS = Map.of("transferredMoney", "YES", "sharedInformation", "NO",
            "installedApp", "UNKNOWN", "contactContinues", "UNKNOWN");
    private static final List<VerifiedReference> REFS = List.of(new VerifiedReference("REF_A", "제목", "문단"));
    private static final UUID ID = UUID.randomUUID();

    @Test
    void planRequestRules() {
        assertDoesNotThrow(() -> new SolutionAssistPlanRequest("질문", null, FACTS, List.of(), 3, ID));
        assertDoesNotThrow(() -> new SolutionAssistPlanRequest(null, SolutionAssistIntent.GENERAL, FACTS,
                List.of("installedApp"), 0, ID));
        var threeFacts = new HashMap<>(FACTS);
        threeFacts.remove("installedApp");
        var extraFact = new HashMap<>(FACTS);
        extraFact.put("fraudType", "YES");
        var badValue = new HashMap<>(FACTS);
        badValue.put("installedApp", "MAYBE");
        var nullKey = new ArrayList<String>();
        nullKey.add(null);
        invalid(() -> new SolutionAssistPlanRequest(null, null, FACTS, List.of(), 3, ID));
        invalid(() -> new SolutionAssistPlanRequest("", null, FACTS, List.of(), 3, ID));
        invalid(() -> new SolutionAssistPlanRequest(" ", null, FACTS, List.of(), 3, ID));
        invalid(() -> new SolutionAssistPlanRequest("x".repeat(1001), null, FACTS, List.of(), 3, ID));
        invalid(() -> new SolutionAssistPlanRequest("질문", null, threeFacts, List.of(), 3, ID));
        invalid(() -> new SolutionAssistPlanRequest("질문", null, extraFact, List.of(), 3, ID));
        invalid(() -> new SolutionAssistPlanRequest("질문", null, badValue, List.of(), 3, ID));
        invalid(() -> new SolutionAssistPlanRequest("질문", null, null, List.of(), 3, ID));
        invalid(() -> new SolutionAssistPlanRequest("질문", null, FACTS, null, 3, ID));
        invalid(() -> new SolutionAssistPlanRequest("질문", null, FACTS, nullKey, 3, ID));
        invalid(() -> new SolutionAssistPlanRequest("질문", null, FACTS, List.of("fraudType"), 3, ID));
        invalid(() -> new SolutionAssistPlanRequest("질문", null, FACTS, List.of("installedApp", "installedApp"), 3, ID));
        invalid(() -> new SolutionAssistPlanRequest("질문", null, FACTS, List.of(), -1, ID));
        invalid(() -> new SolutionAssistPlanRequest("질문", null, FACTS, List.of(), 4, ID));
        invalid(() -> new SolutionAssistPlanRequest("질문", null, FACTS, List.of(), 3, null));
    }

    @Test
    void answerRequestRules() {
        assertDoesNotThrow(() -> new SolutionAssistAnswerRequest(null, SolutionAssistIntent.GENERAL, FACTS, REFS, ID));
        invalid(() -> new SolutionAssistAnswerRequest("질문", null, FACTS, REFS, ID));
        invalid(() -> new SolutionAssistAnswerRequest("질문", SolutionAssistIntent.GENERAL, FACTS, List.of(), ID));
        invalid(() -> new SolutionAssistAnswerRequest("질문", SolutionAssistIntent.GENERAL, FACTS,
                List.of(REFS.getFirst(), REFS.getFirst()), ID));
        invalid(() -> new SolutionAssistAnswerRequest("", SolutionAssistIntent.GENERAL, FACTS, REFS, ID));
        invalid(() -> new SolutionAssistAnswerRequest("질문", SolutionAssistIntent.GENERAL, Map.of(), REFS, ID));
    }

    @Test
    void resultStatusFieldCombinations() {
        var clarification = new SolutionAssistClarification("installedApp", "앱을 설치했나요?",
                SolutionAssistClarification.OPTIONS);
        invalid(() -> new SolutionAssistPlanResult(SolutionAssistStatus.ANSWERED, SolutionAssistIntent.GENERAL,
                null, List.of(), null));
        invalid(() -> new SolutionAssistPlanResult(SolutionAssistStatus.NEED_USER_INPUT, null, clarification,
                List.of(), null));
        invalid(() -> new SolutionAssistPlanResult(null, null, null, List.of(), SolutionAssistReason.NO_EVIDENCE));
        invalid(() -> new SolutionAssistAnswerResult(SolutionAssistStatus.EVIDENCE_READY, "답", List.of("REF_A"), null));
        invalid(() -> new SolutionAssistAnswerResult(SolutionAssistStatus.ANSWERED, "답", List.of("REF_A"),
                SolutionAssistReason.NO_EVIDENCE));
        assertEquals(SolutionAssistClarification.OPTIONS, clarification.options());
    }

    @Test
    void toStringNeverContainsRawText() {
        String question = "내 계좌 110-123-456789 비밀질문";
        var plan = new SolutionAssistPlanRequest(question, null, FACTS, List.of(), 3, ID);
        var answerRequest = new SolutionAssistAnswerRequest(question, SolutionAssistIntent.GENERAL, FACTS,
                List.of(new VerifiedReference("REF_A", "비밀제목", "비밀문단")), ID);
        var clarification = new SolutionAssistClarification("installedApp", "비밀추가질문",
                SolutionAssistClarification.OPTIONS);
        var planResult = new SolutionAssistPlanResult(SolutionAssistStatus.NEED_USER_INPUT,
                SolutionAssistIntent.GENERAL, clarification, List.of(), null);
        var answer = new SolutionAssistAnswerResult(SolutionAssistStatus.ANSWERED, "비밀답변", List.of("REF_A"), null);
        for (Object value : List.of(plan, answerRequest, planResult, answer)) {
            assertFalse(value.toString().contains("비밀"), value.getClass().getSimpleName());
            assertFalse(value.toString().contains("110-123"), value.getClass().getSimpleName());
        }
    }

    private static void invalid(Executable executable) {
        assertThrows(IllegalArgumentException.class, executable);
    }
}
