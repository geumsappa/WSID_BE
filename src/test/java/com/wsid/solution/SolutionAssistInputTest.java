package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.FactValue;
import com.wsid.solution.dto.SendSolutionMessageRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class SolutionAssistInputTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUEST_ID = UUID.randomUUID().toString();
    private static final String QUESTION_ID = UUID.randomUUID().toString();

    private static SendSolutionMessageRequest parse(String body) {
        return SendSolutionMessageRequest.from(JSON.readTree(body));
    }

    private static String clarification(String extra) {
        return "{\"requestId\":\"" + REQUEST_ID + "\",\"stateVersion\":4,\"purpose\":\"CLARIFICATION_ANSWER\","
                + "\"inputKind\":\"CHOICE\",\"questionId\":\"" + QUESTION_ID + "\",\"selectedCode\":\"YES\"" + extra + "}";
    }

    private static void invalid(String body) {
        var error = assertThrows(ApiException.class, () -> parse(body), body);
        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode());
    }

    @ParameterizedTest @ValueSource(strings={"QUESTION","FACT_UPDATE"})
    void acceptsTextPurpose(String purpose) {
        var node=JSON.readTree("{\"requestId\":\""+UUID.randomUUID()+"\",\"stateVersion\":0,\"inputKind\":\"TEXT\",\"content\":\"상황\",\"purpose\":\""+purpose+"\"}");
        assertEquals(purpose,SendSolutionMessageRequest.from(node).purpose().name());
    }

    @ParameterizedTest @ValueSource(strings={"null","true","1","\"\"","\"NEW\"","\"question\""})
    void rejectsInvalidPurpose(String purpose) {
        invalid("{\"requestId\":\""+UUID.randomUUID()+"\",\"stateVersion\":0,\"inputKind\":\"TEXT\",\"content\":\"상황\",\"purpose\":"+purpose+"}");
    }

    @Test
    void omittedPurposeKeepsExistingFactUpdateBehavior() {
        var text = parse("{\"requestId\":\"" + REQUEST_ID + "\",\"stateVersion\":0,\"inputKind\":\"TEXT\",\"content\":\"상황\"}");
        var choice = parse("{\"requestId\":\"" + REQUEST_ID + "\",\"stateVersion\":0,\"inputKind\":\"CHOICE\","
                + "\"factKey\":\"installedApp\",\"selectedCode\":\"NO\"}");
        assertEquals(SendSolutionMessageRequest.Purpose.FACT_UPDATE, text.purpose());
        assertEquals(SendSolutionMessageRequest.Purpose.FACT_UPDATE, choice.purpose());
        assertNull(choice.questionId());
    }

    @Test
    void parsesClarificationAnswerWithServerQuestionIdOnly() {
        var request = parse(clarification(""));
        assertEquals(SendSolutionMessageRequest.Purpose.CLARIFICATION_ANSWER, request.purpose());
        assertEquals(UUID.fromString(QUESTION_ID), request.questionId());
        assertEquals(FactValue.YES, request.selected());
        assertEquals(4, request.stateVersion());
        assertFalse(request.isText());
    }

    @Test
    void rejectsInvalidPurposeAndInputKindCombinations() {
        // QUESTION은 TEXT만, CLARIFICATION_ANSWER는 CHOICE만 허용해요.
        invalid("{\"requestId\":\"" + REQUEST_ID + "\",\"stateVersion\":0,\"purpose\":\"QUESTION\",\"inputKind\":\"CHOICE\","
                + "\"factKey\":\"installedApp\",\"selectedCode\":\"YES\"}");
        invalid("{\"requestId\":\"" + REQUEST_ID + "\",\"stateVersion\":0,\"purpose\":\"CLARIFICATION_ANSWER\","
                + "\"inputKind\":\"TEXT\",\"content\":\"네\",\"questionId\":\"" + QUESTION_ID + "\"}");
        // questionId는 CLARIFICATION_ANSWER에서만 받아요.
        invalid("{\"requestId\":\"" + REQUEST_ID + "\",\"stateVersion\":0,\"purpose\":\"QUESTION\",\"inputKind\":\"TEXT\","
                + "\"content\":\"질문\",\"questionId\":\"" + QUESTION_ID + "\"}");
        invalid("{\"requestId\":\"" + REQUEST_ID + "\",\"stateVersion\":0,\"purpose\":\"FACT_UPDATE\",\"inputKind\":\"CHOICE\","
                + "\"factKey\":\"installedApp\",\"selectedCode\":\"YES\",\"questionId\":\"" + QUESTION_ID + "\"}");
    }

    @Test
    void rejectsInvalidClarificationAnswerFields() {
        // factKey는 서버가 저장한 대기 질문에서 정하므로 클라이언트가 보내면 안 돼요.
        invalid(clarification(",\"factKey\":\"installedApp\""));
        invalid(clarification(",\"content\":\"네\""));
        invalid(clarification(",\"extra\":1"));
        invalid(clarification("").replace(",\"questionId\":\"" + QUESTION_ID + "\"", ""));
        invalid(clarification("").replace("\"questionId\":\"" + QUESTION_ID + "\"", "\"questionId\":null"));
        invalid(clarification("").replace("\"questionId\":\"" + QUESTION_ID + "\"", "\"questionId\":\"q-7f3a\""));
        invalid(clarification("").replace("\"questionId\":\"" + QUESTION_ID + "\"", "\"questionId\":123"));
        invalid(clarification("").replace("\"YES\"", "\"MAYBE\""));
        invalid(clarification("").replace("\"YES\"", "\"yes\""));
        invalid(clarification("").replace("\"YES\"", "null"));
        invalid(clarification("").replace(",\"selectedCode\":\"YES\"", ""));
        invalid(clarification("").replace("\"stateVersion\":4", "\"stateVersion\":-1"));
        invalid(clarification("").replace("\"stateVersion\":4", "\"stateVersion\":\"4\""));
        invalid(clarification("").replace("\"stateVersion\":4", "\"stateVersion\":2147483647"));
        invalid(clarification("").replace("\"requestId\":\"" + REQUEST_ID + "\"", "\"requestId\":\"not-a-uuid\""));
        invalid(clarification("").replace("\"inputKind\":\"CHOICE\"", "\"inputKind\":null"));
    }
}
