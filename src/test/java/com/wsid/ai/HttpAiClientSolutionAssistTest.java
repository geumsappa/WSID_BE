package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.wsid.ai.dto.SolutionAssistAnswerRequest;
import com.wsid.ai.dto.SolutionAssistIntent;
import com.wsid.ai.dto.SolutionAssistPlanRequest;
import com.wsid.ai.dto.SolutionAssistReason;
import com.wsid.ai.dto.SolutionAssistStatus;
import com.wsid.ai.dto.SolutionCandidateReference;
import com.wsid.ai.dto.VerifiedReference;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class HttpAiClientSolutionAssistTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> FACTS = Map.of("transferredMoney", "YES", "sharedInformation", "NO",
            "installedApp", "UNKNOWN", "contactContinues", "UNKNOWN");
    private static final String NEED_INPUT = """
            {"success":true,"data":{"status":"NEED_USER_INPUT","questionIntent":"DEVICE_SAFETY",
            "clarification":{"factKey":"installedApp","content":"상대방이 알려준 앱을 설치한 적이 있나요?",
            "options":["YES","NO","UNKNOWN"]},"candidateReferences":[],"reasonCode":null}}""";
    private static final String EVIDENCE = """
            {"success":true,"data":{"status":"EVIDENCE_READY","questionIntent":"DEVICE_SAFETY","clarification":null,
            "candidateReferences":[{"code":"APP_REMOVE_GUIDE","version":"1"}],"reasonCode":null}}""";
    private static final String UNABLE = """
            {"success":true,"data":{"status":"UNABLE_TO_ANSWER","questionIntent":null,"clarification":null,
            "candidateReferences":[],"reasonCode":"OUT_OF_SCOPE"}}""";
    private static final String ANSWERED = """
            {"success":true,"data":{"status":"ANSWERED","answer":"설치한 앱은 바로 삭제하고 안내를 따라 주세요.",
            "referenceCodes":["APP_REMOVE_GUIDE"],"reasonCode":null}}""";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpAiClient client = new HttpAiClient(new AiProperties("http", "http://ai.test", "test-key",
            Duration.ofSeconds(2), Duration.ofSeconds(20)), builder);
    private final SolutionAssistPlanRequest plan = new SolutionAssistPlanRequest(
            "앱 관련해서 어떻게 해야 하나요?", null, FACTS, List.of(), 3, UUID.randomUUID());
    private final SolutionAssistAnswerRequest answer = new SolutionAssistAnswerRequest(
            "앱 관련해서 어떻게 해야 하나요?", SolutionAssistIntent.DEVICE_SAFETY, FACTS,
            List.of(new VerifiedReference("APP_REMOVE_GUIDE", "악성 앱 삭제 방법", "설치한 앱을 삭제합니다.")),
            UUID.randomUUID());

    @Test
    void planSendsExactContractAndParsesClarification() {
        server.expect(requestTo("http://ai.test/internal/ai/solutions/assist")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", "test-key"))
                .andExpect(header("X-Request-Id", plan.requestId().toString()))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"phase":"PLAN","question":"앱 관련해서 어떻게 해야 하나요?","questionIntent":null,
                        "facts":{"transferredMoney":"YES","sharedInformation":"NO","installedApp":"UNKNOWN",
                        "contactContinues":"UNKNOWN"},"askedFactKeys":[],"remainingClarifications":3,"fraudType":null,"history":null}""",
                        JsonCompareMode.STRICT))
                .andRespond(withSuccess(NEED_INPUT, MediaType.APPLICATION_JSON));
        var result = client.planSolutionAssist(plan);
        assertEquals(SolutionAssistStatus.NEED_USER_INPUT, result.status());
        assertEquals(SolutionAssistIntent.DEVICE_SAFETY, result.questionIntent());
        assertEquals("installedApp", result.clarification().factKey());
        assertEquals(List.of("YES", "NO", "UNKNOWN"), result.clarification().options());
        server.verify();
    }

    @Test
    void v12PlanSendsConfirmedConditionsAndParsesConditionClarification() {
        String code = "REF_LAW_REFUND_PROCEDURE_V1_P2";
        var asked = com.wsid.ai.dto.SolutionAssistCondition.of(code, "적용: 전화·문자로 속여 송금하게 한 경우");
        var done = List.of(com.wsid.ai.dto.SolutionAssistConfirmedCondition.of(asked, "YES"));
        var request = new SolutionAssistPlanRequest(null, SolutionAssistIntent.TRANSFER_RESPONSE, FACTS, List.of(), 2,
                UUID.randomUUID(), null, null, done);
        var next = com.wsid.ai.dto.SolutionAssistCondition.of(code, "제외: 물건·서비스를 주겠다며 속인 사기");
        server.expect(content().json("""
                {"confirmedConditions":[{"conditionId":"%s","referenceCode":"%s","quote":"적용: 전화·문자로 속여 송금하게 한 경우","answer":"YES"}]}"""
                .formatted(asked.conditionId(), code), JsonCompareMode.LENIENT))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"status":"NEED_USER_INPUT","questionIntent":"TRANSFER_RESPONSE",
                        "clarification":{"kind":"CONDITION","factKey":null,"condition":{"conditionId":"%s",
                        "referenceCode":"%s","quote":"제외: 물건·서비스를 주겠다며 속인 사기"},
                        "content":"물건이나 서비스를 받기로 하고 보내신 돈인가요?","options":["YES","NO","UNKNOWN"]},
                        "candidateReferences":[],"reasonCode":null}}""".formatted(next.conditionId(), code), MediaType.APPLICATION_JSON));
        var result = client.planSolutionAssist(request);
        assertTrue(result.clarification().isCondition());
        assertNull(result.clarification().factKey());
        assertEquals(next, result.clarification().condition());
        server.verify();
    }

    @Test
    void tamperedConditionIdIsRejected() {
        var request = new SolutionAssistPlanRequest("질문", null, FACTS, List.of(), 3, UUID.randomUUID(), null, null, List.of());
        server.expect(requestTo("http://ai.test/internal/ai/solutions/assist")).andRespond(withSuccess("""
                {"success":true,"data":{"status":"NEED_USER_INPUT","questionIntent":"TRANSFER_RESPONSE",
                "clarification":{"kind":"CONDITION","factKey":null,"condition":{"conditionId":"REF_X#000000000000",
                "referenceCode":"REF_X","quote":"제외: 물건·서비스를 주겠다며 속인 사기"},
                "content":"물건을 받기로 하고 보내신 돈인가요?","options":["YES","NO","UNKNOWN"]},
                "candidateReferences":[],"reasonCode":null}}""", MediaType.APPLICATION_JSON));
        assertThrows(AiUnavailableException.class, () -> client.planSolutionAssist(request));
    }

    @Test
    void followUpPlanSendsNullQuestionWithIntentAndAskedKeys() {
        var followUp = new SolutionAssistPlanRequest(null, SolutionAssistIntent.DEVICE_SAFETY,
                Map.of("transferredMoney", "YES", "sharedInformation", "NO", "installedApp", "YES",
                        "contactContinues", "UNKNOWN"), List.of("installedApp"), 2, UUID.randomUUID());
        server.expect(content().json("""
                {"phase":"PLAN","question":null,"questionIntent":"DEVICE_SAFETY","facts":{"transferredMoney":"YES",
                "sharedInformation":"NO","installedApp":"YES","contactContinues":"UNKNOWN"},
                "askedFactKeys":["installedApp"],"remainingClarifications":2,"fraudType":null,"history":null}""", JsonCompareMode.STRICT))
                .andRespond(withSuccess(EVIDENCE, MediaType.APPLICATION_JSON));
        var result = client.planSolutionAssist(followUp);
        assertEquals(List.of(new SolutionCandidateReference("APP_REMOVE_GUIDE", "1")), result.candidateReferences());
        server.verify();
    }

    @Test
    void planUnableToAnswerAllowsNullIntent() {
        server.expect(anything()).andRespond(withSuccess(UNABLE, MediaType.APPLICATION_JSON));
        var result = client.planSolutionAssist(plan);
        assertEquals(SolutionAssistReason.OUT_OF_SCOPE, result.reasonCode());
        assertNull(result.questionIntent());
    }

    @Test
    void answerSendsExactContractAndParsesAnswer() {
        server.expect(requestTo("http://ai.test/internal/ai/solutions/assist"))
                .andExpect(header("X-Request-Id", answer.requestId().toString()))
                .andExpect(content().json("""
                        {"phase":"ANSWER","question":"앱 관련해서 어떻게 해야 하나요?","questionIntent":"DEVICE_SAFETY",
                        "facts":{"transferredMoney":"YES","sharedInformation":"NO","installedApp":"UNKNOWN",
                        "contactContinues":"UNKNOWN"},"verifiedReferences":[{"code":"APP_REMOVE_GUIDE",
                        "title":"악성 앱 삭제 방법","excerpt":"설치한 앱을 삭제합니다."}],"fraudType":null,"history":null}""", JsonCompareMode.STRICT))
                .andRespond(withSuccess(ANSWERED, MediaType.APPLICATION_JSON));
        var result = client.answerSolutionAssist(answer);
        assertEquals(SolutionAssistStatus.ANSWERED, result.status());
        assertEquals(List.of("APP_REMOVE_GUIDE"), result.referenceCodes());
        server.verify();
    }

    @Test
    void answerUnableToAnswerIsAccepted() {
        server.expect(anything()).andRespond(withSuccess("""
                {"success":true,"data":{"status":"UNABLE_TO_ANSWER","answer":null,"referenceCodes":[],
                "reasonCode":"NO_EVIDENCE"}}""", MediaType.APPLICATION_JSON));
        assertEquals(SolutionAssistReason.NO_EVIDENCE, client.answerSolutionAssist(answer).reasonCode());
    }

    @Test
    void ignoresExtraFieldsWithoutKeepingThem() {
        server.expect(anything()).andRespond(withSuccess(ANSWERED.replace("\"data\":{",
                "\"debug\":\"private\",\"data\":{\"prompt\":\"private\","), MediaType.APPLICATION_JSON));
        assertFalse(JSON.writeValueAsString(client.answerSolutionAssist(answer)).contains("private"));
    }

    static Stream<String> invalidPlanResponses() {
        var need = JSON.readTree(NEED_INPUT);
        var evidence = JSON.readTree(EVIDENCE);
        var unable = JSON.readTree(UNABLE);
        Stream<String> envelope = Stream.of("", "{", "null", "[]", "{}", "{\"success\":true}",
                "{\"success\":true,\"data\":[]}", NEED_INPUT.replace("\"success\":true", "\"success\":\"true\""),
                NEED_INPUT.replace("\"success\":true", "\"success\":false"));
        Stream<String> fields = Stream.of(
                missing(need, "status"), missing(need, "questionIntent"), missing(need, "clarification"),
                missing(need, "candidateReferences"), missing(need, "reasonCode"),
                changed(need, "status", null), changed(need, "status", "need_user_input"), changed(need, "status", 1),
                changed(need, "status", "ANSWERED"), changed(need, "status", "UNKNOWN_STATUS"),
                changed(need, "questionIntent", null), changed(need, "questionIntent", "OTHER"),
                changed(need, "questionIntent", true), changed(need, "clarification", null),
                changed(need, "clarification", "installedApp"), changed(need, "reasonCode", "OUT_OF_SCOPE"),
                changed(need, "candidateReferences", List.of(Map.of("code", "A", "version", "1"))),
                changed(need, "candidateReferences", null), changed(need, "reasonCode", "OTHER"));
        Stream<String> clarification = Stream.of(
                clarification(need, "factKey", "fraudType"), clarification(need, "factKey", 1),
                clarification(need, "factKey", null), clarification(need, "content", ""),
                clarification(need, "content", " \n "), clarification(need, "content", "x".repeat(201)),
                clarification(need, "content", 1), clarification(need, "options", List.of("YES", "NO")),
                clarification(need, "options", List.of("NO", "YES", "UNKNOWN")),
                clarification(need, "options", List.of("YES", "NO", "UNKNOWN", "MAYBE")),
                clarification(need, "options", "YES"), clarification(need, "options", List.of(1, 2, 3)),
                clarification(need, "content", "앱 설치 여부를 010-0000-0000 으로 알려주세요"),
                clarification(need, "content", "https://example.invalid 에서 확인했나요?"),
                // 이미 확정된 사실(YES/NO)은 다시 묻지 못해요.
                clarification(need, "factKey", "transferredMoney"), clarification(need, "factKey", "sharedInformation"));
        Stream<String> evidenceCases = Stream.of(changed(evidence, "candidateReferences", List.of()),
                changed(evidence, "candidateReferences", List.of(Map.of("code", "app_guide", "version", "1"))),
                changed(evidence, "candidateReferences", List.of(Map.of("code", "APP", "version", ""))),
                changed(evidence, "candidateReferences", List.of(Map.of("code", "APP", "version", 1))),
                changed(evidence, "candidateReferences", List.of(Map.of("code", "APP"))),
                changed(evidence, "candidateReferences", List.of("APP")),
                changed(evidence, "candidateReferences", Arrays.asList((Object) null)),
                changed(evidence, "candidateReferences", List.of(Map.of("code", "APP", "version", "1"),
                        Map.of("code", "APP", "version", "2"))),
                changed(evidence, "candidateReferences", IntStream.range(0, 11)
                        .mapToObj(i -> Map.of("code", "REF_" + i, "version", "1")).toList()),
                changed(evidence, "questionIntent", null), changed(evidence, "reasonCode", "NO_EVIDENCE"));
        Stream<String> unableCases = Stream.of(changed(unable, "reasonCode", null),
                changed(unable, "candidateReferences", List.of(Map.of("code", "APP", "version", "1"))));
        return Stream.of(envelope, fields, clarification, evidenceCases, unableCases).flatMap(s -> s);
    }

    @ParameterizedTest
    @MethodSource("invalidPlanResponses")
    void rejectsInvalidPlanResponses(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertPrivateFailure(() -> client.planSolutionAssist(plan));
        server.verify();
    }

    @Test
    void rejectsClarificationWhenLimitReachedOrAlreadyAsked() {
        var noneLeft = new SolutionAssistPlanRequest("질문", null, FACTS, List.of(), 0, UUID.randomUUID());
        var alreadyAsked = new SolutionAssistPlanRequest("질문", null, FACTS, List.of("installedApp"), 2, UUID.randomUUID());
        server.expect(anything()).andRespond(withSuccess(NEED_INPUT, MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(NEED_INPUT, MediaType.APPLICATION_JSON));
        assertPrivateFailure(() -> client.planSolutionAssist(noneLeft));
        assertPrivateFailure(() -> client.planSolutionAssist(alreadyAsked));
        server.verify();
    }

    @Test
    void rejectsIntentChangeOnFollowUp() {
        var followUp = new SolutionAssistPlanRequest(null, SolutionAssistIntent.TRANSFER_RESPONSE, FACTS,
                List.of("transferredMoney"), 2, UUID.randomUUID());
        server.expect(anything()).andRespond(withSuccess(EVIDENCE, MediaType.APPLICATION_JSON));
        assertPrivateFailure(() -> client.planSolutionAssist(followUp));
    }

    static Stream<String> invalidAnswerResponses() {
        var base = JSON.readTree(ANSWERED);
        return Stream.of("", "{}", "{\"success\":true,\"data\":null}",
                missing(base, "answer"), missing(base, "referenceCodes"), missing(base, "reasonCode"),
                missing(base, "status"), changed(base, "status", "EVIDENCE_READY"),
                changed(base, "status", "NEED_USER_INPUT"), changed(base, "answer", null), changed(base, "answer", ""),
                changed(base, "answer", 1), changed(base, "answer", "x".repeat(2001)),
                changed(base, "referenceCodes", List.of()), changed(base, "referenceCodes", "APP_REMOVE_GUIDE"),
                changed(base, "referenceCodes", List.of(1)), changed(base, "referenceCodes", List.of("REF_NEW")),
                changed(base, "referenceCodes", List.of("APP_REMOVE_GUIDE", "REF_NEW")),
                changed(base, "referenceCodes", List.of("APP_REMOVE_GUIDE", "APP_REMOVE_GUIDE")),
                changed(base, "reasonCode", "NO_EVIDENCE"),
                changed(base, "answer", "고객센터 1588-0000으로 전화하세요."),
                changed(base, "answer", "[여기](https://example.invalid)를 누르세요."),
                // UNABLE_TO_ANSWER에 답변·인용이 섞이거나 PLAN 전용 이유를 쓰면 안 돼요.
                "{\"success\":true,\"data\":{\"status\":\"UNABLE_TO_ANSWER\",\"answer\":\"x\",\"referenceCodes\":[],"
                        + "\"reasonCode\":\"NO_EVIDENCE\"}}",
                "{\"success\":true,\"data\":{\"status\":\"UNABLE_TO_ANSWER\",\"answer\":null,"
                        + "\"referenceCodes\":[\"APP_REMOVE_GUIDE\"],\"reasonCode\":\"NO_EVIDENCE\"}}",
                "{\"success\":true,\"data\":{\"status\":\"UNABLE_TO_ANSWER\",\"answer\":null,\"referenceCodes\":[],"
                        + "\"reasonCode\":\"LIMIT_REACHED\"}}",
                "{\"success\":true,\"data\":{\"status\":\"UNABLE_TO_ANSWER\",\"answer\":null,\"referenceCodes\":[],"
                        + "\"reasonCode\":null}}");
    }

    @ParameterizedTest
    @MethodSource("invalidAnswerResponses")
    void rejectsInvalidAnswerResponses(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertPrivateFailure(() -> client.answerSolutionAssist(answer));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 422, 429, 500, 503})
    void rejectsHttpErrorsEvenWithSuccessfulBody(int status) {
        server.expect(anything()).andRespond(withRawStatus(status).body(NEED_INPUT));
        assertPrivateFailure(() -> client.planSolutionAssist(plan));
        server.verify();
    }

    @Test
    void transportFailuresDoNotKeepCauseAndAreNotRetried() {
        server.expect(anything()).andRespond(withException(new SocketTimeoutException("private")));
        server.expect(anything()).andRespond(withSuccess("<html>private</html>", MediaType.TEXT_HTML));
        assertPrivateFailure(() -> client.planSolutionAssist(plan));
        assertPrivateFailure(() -> client.answerSolutionAssist(answer));
        server.verify();
    }

    @Test
    void nullRequestsDoNotCallAi() {
        assertThrows(IllegalArgumentException.class, () -> client.planSolutionAssist(null));
        assertThrows(IllegalArgumentException.class, () -> client.answerSolutionAssist(null));
        server.verify();
    }

    private static void assertPrivateFailure(Runnable call) {
        var error = assertThrows(AiUnavailableException.class, call::run);
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.getMessage().contains("private"));
        assertFalse(error.getMessage().contains("앱 관련해서"));
    }

    private static String changed(JsonNode original, String field, Object value) {
        var root = original.deepCopy();
        ((ObjectNode) root.path("data")).set(field, JSON.valueToTree(value));
        return root.toString();
    }

    private static String clarification(JsonNode original, String field, Object value) {
        var root = original.deepCopy();
        ((ObjectNode) root.path("data").path("clarification")).set(field, JSON.valueToTree(value));
        return root.toString();
    }

    private static String missing(JsonNode original, String field) {
        var root = original.deepCopy();
        ((ObjectNode) root.path("data")).remove(field);
        return root.toString();
    }
}
