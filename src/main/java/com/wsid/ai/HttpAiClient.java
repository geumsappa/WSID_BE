package com.wsid.ai;

import com.wsid.ai.dto.AiEnvelope;
import com.wsid.ai.dto.OcrExtractResult;
import com.wsid.ai.dto.OcrImage;
import com.wsid.ai.dto.ScenarioGenerateRequest;
import com.wsid.ai.dto.ScenarioGenerateResult;
import com.wsid.ai.dto.SimulationReplyRequest;
import com.wsid.ai.dto.SimulationReplyResult;
import com.wsid.ai.dto.SolutionAssistAnswerRequest;
import com.wsid.ai.dto.SolutionAssistAnswerResult;
import com.wsid.ai.dto.SolutionAssistClarification;
import com.wsid.ai.dto.SolutionAssistIntent;
import com.wsid.ai.dto.SolutionAssistPlanRequest;
import com.wsid.ai.dto.SolutionAssistPlanResult;
import com.wsid.ai.dto.SolutionAssistReason;
import com.wsid.ai.dto.SolutionAssistStatus;
import com.wsid.ai.dto.SolutionCandidateReference;
import com.wsid.ai.dto.SolutionExtractRequest;
import com.wsid.ai.dto.SolutionExtractResult;
import com.wsid.ai.dto.SolutionExplainRequest;
import com.wsid.ai.dto.SolutionExplainResult;
import com.wsid.ai.dto.TrainingTurnRequest;
import com.wsid.ai.dto.TrainingTurnSuggestion;
import com.wsid.ai.dto.TrainingEvaluateRequest;
import com.wsid.ai.dto.TrainingEvaluateResult;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** AI 서비스(FastAPI)를 HTTP로 호출해요. 타임아웃이 나면 AiUnavailableException으로 바꿔 던져요. */
public class HttpAiClient implements AiClient {

    static final String OCR_PATH = "/internal/ai/ocr/extract";
    static final String OCR_FAILED = "OCR_FAILED";
    static final String SCENARIO_PATH = "/internal/ai/scenarios/generate";
    static final String SIMULATION_PATH = "/internal/ai/simulation/reply";
    static final String EVALUATION_PATH = "/internal/ai/training/evaluate";
    static final String SOLUTION_EXTRACT_PATH = "/internal/ai/solutions/extract";
    static final String SOLUTION_EXPLAIN_PATH = "/internal/ai/solutions/explain";
    static final String SOLUTION_ASSIST_PATH = "/internal/ai/solutions/assist";
    private static final ObjectMapper SCENARIO_JSON = new ObjectMapper();
    private static final ObjectMapper SIMULATION_JSON = new ObjectMapper();
    private static final ObjectMapper EVALUATION_JSON = new ObjectMapper();
    private static final ObjectMapper SOLUTION_JSON = new ObjectMapper();
    private static final ObjectMapper EXPLANATION_JSON = new ObjectMapper();

    private static final ParameterizedTypeReference<AiEnvelope<OcrExtractResult>> OCR_RESPONSE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final RestClient scenarioRestClient;
    private final RestClient evaluationRestClient;
    private final RestClient assistRestClient;

    public HttpAiClient(AiProperties properties) {
        this(properties, configuredBuilder(properties),
                configuredBuilder(properties, properties.scenarioReadTimeout()),
                configuredBuilder(properties, properties.evaluationReadTimeout()),
                configuredBuilder(properties, properties.assistReadTimeout()));
    }

    // The injected builder owns the transport; tests can bind MockRestServiceServer.
    public HttpAiClient(AiProperties properties, RestClient.Builder builder) {
        this(properties, builder, builder, builder);
    }

    public HttpAiClient(AiProperties properties, RestClient.Builder builder, RestClient.Builder scenarioBuilder) {
        this(properties, builder, scenarioBuilder, builder);
    }

    public HttpAiClient(AiProperties properties, RestClient.Builder builder,
            RestClient.Builder scenarioBuilder, RestClient.Builder evaluationBuilder) {
        this(properties, builder, scenarioBuilder, evaluationBuilder, builder);
    }

    public HttpAiClient(AiProperties properties, RestClient.Builder builder,
            RestClient.Builder scenarioBuilder, RestClient.Builder evaluationBuilder, RestClient.Builder assistBuilder) {
        this.restClient = builder.baseUrl(properties.baseUrl())
                .defaultHeader("X-Internal-Api-Key", properties.apiKey()).build();
        this.scenarioRestClient = scenarioBuilder.baseUrl(properties.baseUrl())
                .defaultHeader("X-Internal-Api-Key", properties.apiKey()).build();
        this.evaluationRestClient = evaluationBuilder.baseUrl(properties.baseUrl())
                .defaultHeader("X-Internal-Api-Key", properties.apiKey()).build();
        this.assistRestClient = assistBuilder.baseUrl(properties.baseUrl())
                .defaultHeader("X-Internal-Api-Key", properties.apiKey()).build();
    }

    public static RestClient.Builder configuredBuilder(AiProperties properties) {
        return configuredBuilder(properties, properties.readTimeout());
    }

    private static RestClient.Builder configuredBuilder(AiProperties properties, Duration readTimeout) {
        // FastAPI(uvicorn)는 평문 HTTP의 h2c 업그레이드를 지원하지 않아요. JDK 기본값(HTTP/2)은 Upgrade 헤더를
        // 붙여 보내 본문이 깨지므로(422) 내부 AI 호출은 HTTP/1.1로 고정해요.
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }

    @Override
    public SolutionAssistPlanResult planSolutionAssist(SolutionAssistPlanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("상담 assist PLAN 요청이 필요해요.");
        }
        try {
            JsonNode data = postAssist(request, request.requestId());
            var result = new SolutionAssistPlanResult(
                    enumField(data, "status", SolutionAssistStatus.class, false),
                    enumField(data, "questionIntent", SolutionAssistIntent.class, true),
                    clarificationField(data), candidateField(data),
                    enumField(data, "reasonCode", SolutionAssistReason.class, true));
            return SolutionAssistValidator.validate(request, result);
        } catch (RestClientException | tools.jackson.core.JacksonException | IllegalArgumentException e) {
            // 질문·근거 원문이 예외에 담길 수 있어 원인을 보관하지 않아요.
            throw new AiUnavailableException("상담 assist PLAN 호출 또는 응답 검증에 실패했어요.");
        }
    }

    @Override
    public SolutionAssistAnswerResult answerSolutionAssist(SolutionAssistAnswerRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("상담 assist ANSWER 요청이 필요해요.");
        }
        try {
            JsonNode data = postAssist(request, request.requestId());
            JsonNode answer = required(data, "answer");
            if (!answer.isNull() && !answer.isString()) {
                throw new IllegalArgumentException("answer 타입이 올바르지 않아요.");
            }
            var result = new SolutionAssistAnswerResult(
                    enumField(data, "status", SolutionAssistStatus.class, false),
                    answer.isNull() ? null : answer.asString(), stringArray(required(data, "referenceCodes")),
                    enumField(data, "reasonCode", SolutionAssistReason.class, true));
            return SolutionAssistValidator.validate(request, result);
        } catch (RestClientException | tools.jackson.core.JacksonException | IllegalArgumentException e) {
            throw new AiUnavailableException("상담 assist ANSWER 호출 또는 응답 검증에 실패했어요.");
        }
    }

    /** 응답 봉투를 검사하고 data를 돌려줘요. 상태별 필드는 모두 있어야 하며(null 허용 필드 포함) 추가 필드는 무시해요. */
    private JsonNode postAssist(Object body, UUID requestId) {
        JsonNode node = assistRestClient.post().uri(SOLUTION_ASSIST_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", requestId.toString())
                .body(body).retrieve().body(JsonNode.class);
        if (node == null || !node.isObject() || !node.path("success").isBoolean()
                || !node.path("success").asBoolean() || !node.path("data").isObject()) {
            throw new AiUnavailableException("상담 assist 응답 형식이 올바르지 않아요.");
        }
        return node.path("data");
    }

    /**
     * AI-06/07 전용: {"success":true,"data":{...}} 형식과 결과만 오는 형식을 모두 받아요.
     * success 키가 있으면 기존처럼 엄격하게 검사하고, 없으면 결과 필드가 최상위에 있어야 해요.
     * 형식만 너그럽게 받고 필드 내용 검사는 호출한 메서드에서 그대로 해요. 2xx가 아니면 이 단계 전에 실패해요.
     */
    private static JsonNode unwrapLenient(JsonNode node, String required, String label) {
        if (node == null || !node.isObject()) {
            throw new AiUnavailableException(label + " 응답 형식이 올바르지 않아요.");
        }
        if (node.has("success")) {
            if (!node.path("success").isBoolean() || !node.path("success").asBoolean()
                    || !node.path("data").isObject()) {
                throw new AiUnavailableException(label + " 응답 형식이 올바르지 않아요.");
            }
            return node.path("data");
        }
        if (!node.has(required)) {
            throw new AiUnavailableException(label + " 응답 형식이 올바르지 않아요.");
        }
        return node;
    }

    private static JsonNode required(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || value.isMissingNode()) {
            throw new IllegalArgumentException(field + " 필드가 없어요.");
        }
        return value;
    }

    private static <E extends Enum<E>> E enumField(JsonNode data, String field, Class<E> type, boolean nullable) {
        JsonNode value = required(data, field);
        if (value.isNull() && nullable) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException(field + " 타입이 올바르지 않아요.");
        }
        return Enum.valueOf(type, value.asString());
    }

    private static SolutionAssistClarification clarificationField(JsonNode data) {
        JsonNode value = required(data, "clarification");
        if (value.isNull()) {
            return null;
        }
        if (!value.isObject() || !required(value, "factKey").isString() || !required(value, "content").isString()) {
            throw new IllegalArgumentException("clarification 형식이 올바르지 않아요.");
        }
        return new SolutionAssistClarification(value.get("factKey").asString(), value.get("content").asString(),
                stringArray(required(value, "options")));
    }

    private static List<SolutionCandidateReference> candidateField(JsonNode data) {
        JsonNode value = required(data, "candidateReferences");
        if (!value.isArray()) {
            throw new IllegalArgumentException("candidateReferences 형식이 올바르지 않아요.");
        }
        var references = new ArrayList<SolutionCandidateReference>();
        for (JsonNode item : value) {
            if (!item.isObject() || !required(item, "code").isString() || !required(item, "version").isString()) {
                throw new IllegalArgumentException("근거 후보 형식이 올바르지 않아요.");
            }
            references.add(new SolutionCandidateReference(item.get("code").asString(), item.get("version").asString()));
        }
        return references;
    }

    private static List<String> stringArray(JsonNode value) {
        if (!value.isArray()) {
            throw new IllegalArgumentException("배열 형식이 올바르지 않아요.");
        }
        var items = new ArrayList<String>();
        for (JsonNode item : value) {
            if (!item.isString()) {
                throw new IllegalArgumentException("배열 항목 타입이 올바르지 않아요.");
            }
            items.add(item.asString());
        }
        return items;
    }

    @Override
    public SolutionExplainResult explainSolution(SolutionExplainRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("대응 설명 요청이 필요해요.");
        }
        try {
            JsonNode node = restClient.post().uri(SOLUTION_EXPLAIN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", request.requestId().toString())
                    .body(request).retrieve().body(JsonNode.class);
            JsonNode data = unwrapLenient(node, "explanation", "AI-07");
            JsonNode codes = data.path("referenceCodes");
            if (!data.path("explanation").isString() || !codes.isArray()) {
                throw new AiUnavailableException("AI-07 설명 형식이 올바르지 않아요.");
            }
            for (JsonNode code : codes) {
                if (!code.isString()) {
                    throw new AiUnavailableException("AI-07 근거 코드 타입이 올바르지 않아요.");
                }
            }
            var result = EXPLANATION_JSON.treeToValue(data, SolutionExplainResult.class);
            return SolutionExplanationValidator.validate(request, result);
        } catch (RestClientException | tools.jackson.core.JacksonException | IllegalArgumentException e) {
            // HTTP/변환 예외에 사용자·근거 원문이 포함될 수 있어 원인을 보관하지 않아요.
            throw new AiUnavailableException("AI-07 호출 또는 응답 검증에 실패했어요.");
        }
    }

    @Override
    public SolutionExtractResult extractSolutionFacts(SolutionExtractRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("피해 사실 추출 요청이 필요해요.");
        }
        try {
            JsonNode node = restClient.post().uri(SOLUTION_EXTRACT_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", request.requestId().toString())
                    .body(request).retrieve().body(JsonNode.class);
            JsonNode data = unwrapLenient(node, "facts", "AI-06");
            JsonNode facts = data.path("facts");
            JsonNode uncertain = data.path("uncertainFields");
            if (!facts.isObject() || facts.size() != SolutionExtractRequest.FACT_KEYS.size() || !uncertain.isArray()) {
                throw new AiUnavailableException("AI-06 사실 형식이 올바르지 않아요.");
            }
            for (String key : SolutionExtractRequest.FACT_KEYS) {
                if (!facts.path(key).isString()) {
                    throw new AiUnavailableException("AI-06 사실 값 타입이 올바르지 않아요.");
                }
            }
            for (JsonNode key : uncertain) {
                if (!key.isString()) {
                    throw new AiUnavailableException("AI-06 미확정 항목 타입이 올바르지 않아요.");
                }
            }
            return SOLUTION_JSON.treeToValue(data, SolutionExtractResult.class);
        } catch (RestClientException | tools.jackson.core.JacksonException | IllegalArgumentException e) {
            // 피해 설명·추출 근거·응답 본문이 원인 예외에 포함될 수 있어 보관하지 않아요.
            throw new AiUnavailableException("AI-06 호출 또는 응답 검증에 실패했어요.");
        }
    }

    @Override
    public TrainingEvaluateResult evaluateTraining(TrainingEvaluateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("훈련 평가 요청이 필요해요.");
        }
        try {
            JsonNode node = evaluationRestClient.post().uri(EVALUATION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", request.requestId().toString())
                    .body(request).retrieve().body(JsonNode.class);
            if (node == null || !node.isObject() || !node.path("success").isBoolean()
                    || !node.path("success").asBoolean() || !node.path("data").isObject()) {
                throw new AiUnavailableException("AI-05 응답 형식이 올바르지 않아요.");
            }
            JsonNode data = node.path("data");
            JsonNode turnFeedback = data.path("turnFeedback");
            JsonNode page = data.path("mockPageEvaluation");
            if (!data.path("overallFeedback").isTextual()
                    || !isStringArray(data.path("strengths")) || !isStringArray(data.path("improvements"))
                    || !isStringArray(data.path("recommendations"))
                    || !turnFeedback.isArray() || !turnFeedback.isEmpty()
                    || !page.isObject() || !page.path("openedPage").isBoolean()
                    || !page.path("attemptedMockLogin").isBoolean()
                    || !page.path("checkedOfficialChannel").isBoolean()) {
                throw new AiUnavailableException("AI-05 응답 형식이 올바르지 않아요.");
            }
            return EVALUATION_JSON.treeToValue(data, TrainingEvaluateResult.class);
        } catch (RestClientException | tools.jackson.core.JacksonException | IllegalArgumentException e) {
            // 대화·평가·오류 본문이 원인 예외에 포함될 수 있어 보관하지 않아요.
            throw new AiUnavailableException("AI-05 호출 또는 응답 검증에 실패했어요.");
        }
    }

    private static boolean isStringArray(JsonNode node) {
        if (!node.isArray()) return false;
        for (JsonNode item : node) {
            if (!item.isTextual()) return false;
        }
        return true;
    }

    @Override
    public SimulationReplyResult replySimulation(SimulationReplyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("역할극 응답 요청이 필요해요.");
        }
        try {
            // 훈련 턴용 제한을 사용해요. AI-03 생성용 클라이언트와 분리되어 있어요.
            JsonNode node = restClient.post().uri(SIMULATION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", request.requestId().toString())
                    .body(request).retrieve().body(JsonNode.class);
            if (node == null || !node.isObject() || !node.path("success").isBoolean()
                    || !node.path("success").asBoolean() || !node.path("data").isObject()) {
                throw new AiUnavailableException("AI-04 응답 형식이 올바르지 않아요.");
            }
            JsonNode data = node.path("data");
            JsonNode actions = data.path("recognizedActions");
            if (!data.path("reply").isTextual() || !actions.isArray()
                    || !data.path("shouldFinish").isBoolean()
                    || !data.has("mockPage") || !data.path("mockPage").isNull()) {
                throw new AiUnavailableException("AI-04 응답 형식이 올바르지 않아요.");
            }
            for (JsonNode action : actions) {
                if (!action.isTextual()) {
                    throw new AiUnavailableException("AI-04 행동 코드 타입이 올바르지 않아요.");
                }
            }
            return SIMULATION_JSON.treeToValue(data, SimulationReplyResult.class);
        } catch (RestClientException | tools.jackson.core.JacksonException | IllegalArgumentException e) {
            // 본문·대사·원인 예외를 저장하거나 로그에 남기지 않아요.
            throw new AiUnavailableException("AI-04 호출 또는 응답 검증에 실패했어요.");
        }
    }

    @Override
    public ScenarioGenerateResult generateScenario(ScenarioGenerateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("시나리오 생성 요청이 필요해요.");
        }
        try {
            JsonNode node = scenarioRestClient.post().uri(SCENARIO_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", request.requestId().toString())
                    .body(request).retrieve().body(JsonNode.class);
            // DTO 변환 전에 검사해야 문자열 "true"나 숫자가 자동 변환되지 않아요.
            if (node == null || !node.isObject() || !node.path("success").isBoolean()
                    || !node.path("success").asBoolean() || !node.path("data").isObject()) {
                throw new AiUnavailableException("AI-03 응답 형식이 올바르지 않아요.");
            }
            JsonNode data = node.path("data");
            JsonNode scenario = data.path("scenario");
            if (!scenario.isObject() || !scenario.path("title").isTextual()
                    || !scenario.path("description").isTextual()
                    || !data.path("mockPageTemplateCode").isTextual()) {
                throw new AiUnavailableException("AI-03 응답 형식이 올바르지 않아요.");
            }
            // 생성자에서 길이·공백·코드 형식을 검사해요. 카탈로그 대조는 후속 연결의 책임이에요.
            return SCENARIO_JSON.treeToValue(data, ScenarioGenerateResult.class);
        } catch (RestClientException | tools.jackson.core.JacksonException | IllegalArgumentException e) {
            // HTTP/변환 예외에는 응답 본문이 들어갈 수 있어 원인을 보관하지 않아요.
            throw new AiUnavailableException("AI-03 호출 또는 응답 검증에 실패했어요.");
        }
    }

    @Override
    public com.wsid.ai.dto.DiscoveryResult suggestDiscovery(com.wsid.ai.dto.DiscoveryRequest request) {
        try {
            var node = restClient.post().uri("/internal/ai/discovery/next")
                    .header("X-Request-Id", request.requestId().toString()).body(request).retrieve()
                    .body(tools.jackson.databind.JsonNode.class);
            if (node == null || !node.isObject() || !node.path("success").isBoolean()
                    || !node.path("success").asBoolean() || !node.path("data").isObject()
                    || !node.path("data").path("facts").isObject()
                    || !node.path("data").path("nextQuestion").isTextual()
                    || !node.path("data").path("canGenerateScenario").isBoolean())
                throw new AiUnavailableException("AI-01 응답 형식이 올바르지 않아요.");
            var envelope = new tools.jackson.databind.ObjectMapper().treeToValue(node, com.wsid.ai.dto.DiscoveryEnvelope.class);
            if (envelope == null || !Boolean.TRUE.equals(envelope.success()) || (envelope.data() == null || !envelope.data().isValid()))
                throw new AiUnavailableException("AI-01 응답 형식이 올바르지 않아요.");
            return envelope.data();
        } catch (RestClientException | tools.jackson.core.JacksonException e) {
            // HTTP exception messages can contain response bodies. Do not retain them.
            throw new AiUnavailableException("AI-01 호출에 실패했어요.");
        }
    }

    @Override
    public TrainingTurnSuggestion suggestTrainingTurn(TrainingTurnRequest request) {
        try {
            TrainingTurnSuggestion suggestion = restClient.post()
                    .uri("/internal/training/turn")
                    .body(request)
                    .retrieve()
                    .body(TrainingTurnSuggestion.class);
            if (suggestion == null) {
                throw new AiUnavailableException("AI 서비스가 빈 응답을 보냈어요.");
            }
            return suggestion;
        } catch (RestClientException e) {
            throw new AiUnavailableException("AI 서비스 호출에 실패했어요.", e);
        }
    }

    /**
     * AI-02. multipart/form-data의 images 파트로 이미지를 보내요.
     * 오류 상태 코드여도 본문의 error.code가 OCR_FAILED면 실패 결과로 돌려주고,
     * 그 밖의 실패는 AiUnavailableException으로 바꿔요. 본문은 로그에 남기지 않아요.
     */
    @Override
    public OcrExtractResult extractText(List<OcrImage> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("이미지가 한 장 이상 필요해요.");
        }
        try {
            AiEnvelope<OcrExtractResult> envelope = restClient.post()
                    .uri(OCR_PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(toMultipart(images))
                    .exchange((request, response) -> {
                        try {
                            return response.bodyTo(OCR_RESPONSE);
                        } catch (RuntimeException e) {
                            // 프록시 오류 페이지처럼 JSON이 아닌 본문
                            // 예외 메시지에 응답 본문이 섞일 수 있어 원인을 남기지 않아요.
                            throw new AiUnavailableException(
                                    "AI 서비스 OCR 응답을 읽지 못했어요. status=" + response.getStatusCode().value());
                        }
                    });
            return unwrap(envelope);
        } catch (RestClientException | tools.jackson.core.JacksonException e) {
            // 예외 메시지에 응답 본문이 섞일 수 있어 원인을 남기지 않아요.
            throw new AiUnavailableException("AI 서비스 OCR 호출에 실패했어요.");
        }
    }

    private static OcrExtractResult unwrap(AiEnvelope<OcrExtractResult> envelope) {
        if (envelope == null) {
            throw new AiUnavailableException("AI 서비스가 빈 OCR 응답을 보냈어요.");
        }
        if (!envelope.success()) {
            if (OCR_FAILED.equals(envelope.errorCode())) {
                return OcrExtractResult.failed();
            }
            throw new AiUnavailableException("AI 서비스 OCR 처리에 실패했어요. code=" + envelope.errorCode());
        }
        if (envelope.data() == null) {
            throw new AiUnavailableException("AI 서비스 OCR 응답에 data가 없어요.");
        }
        return envelope.data();
    }

    /** reactive 의존성이 필요한 MultipartBodyBuilder 대신 파트마다 HttpEntity를 만들어요. */
    private static MultiValueMap<String, HttpEntity<?>> toMultipart(List<OcrImage> images) {
        MultiValueMap<String, HttpEntity<?>> parts = new LinkedMultiValueMap<>();
        for (int i = 0; i < images.size(); i++) {
            OcrImage image = images.get(i);
            String filename = image.filename() == null || image.filename().isBlank()
                    ? "image-" + (i + 1)
                    : image.filename();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(image.contentType() == null || image.contentType().isBlank()
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(image.contentType()));
            headers.setContentDisposition(ContentDisposition.formData().name("images").filename(filename).build());
            parts.add("images", new HttpEntity<>(new ByteArrayResource(image.content()), headers));
        }
        return parts;
    }
}
