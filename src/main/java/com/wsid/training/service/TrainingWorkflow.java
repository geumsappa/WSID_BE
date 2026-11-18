package com.wsid.training.service;

import com.wsid.ai.TrainingAiGateway;
import com.wsid.auth.CurrentUser;
import com.wsid.common.domain.InputKind;
import com.wsid.common.domain.MessageSender;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.report.domain.ReportEvaluation;
import com.wsid.report.service.ReportService;
import com.wsid.training.domain.TrainingMessage;
import com.wsid.training.domain.MockPhoto;
import com.wsid.training.domain.TrainingPhase;
import com.wsid.training.domain.TrainingRules;
import com.wsid.training.domain.TrainingSession;
import com.wsid.training.domain.TrainingStatus;
import com.wsid.training.dto.TrainingRequests;
import com.wsid.training.dto.TrainingResponses;
import com.wsid.training.repository.TrainingMessageRepository;
import com.wsid.training.repository.TrainingSessionRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class TrainingWorkflow {
    private static final String TYPE = "SECONDHAND_TRADE";
    private static final int MAX_DISCOVERY_TURNS = 5;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final List<TrainingResponses.Option> SAFE_OPTIONS = List.of(
            new TrainingResponses.Option("OFFICIAL_CHANNEL_CHECK", "공식 채널에서 직접 확인할게요."),
            new TrainingResponses.Option("END_CONTACT", "대화를 중단할게요."));
    private static final TrainingResponses.MockPage BANK_NOTICE = TrainingResponses.MockPage.bankNotice();
    private static final Pattern SENSITIVE = Pattern.compile(
            "[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|(?<!\\d)0\\d{1,2}[- .]?[0-9]{3,4}[- .]?[0-9]{4}(?!\\d)"
            + "|(?<!\\d)\\d{6}[- ]?[1-8]\\d{6}(?!\\d)|(?<!\\d)\\d{2,6}-\\d{2,6}-\\d{2,8}(?!\\d)"
            + "|(?<!\\d)(?:\\d[ -]?){9,18}\\d(?!\\d)"
            + "|(?i)(?:비밀번호|인증번호|OTP|password|PIN)\\s*(?:[:=]|은|는)\\s*\\S+|^\\s*\\d{4,8}\\s*$");
    private static final Pattern LIVE_LINK = Pattern.compile("(?i)https?://|www\\.");

    private final CurrentUser currentUser;
    private final TrainingSessionRepository sessions;
    private final TrainingMessageRepository messages;
    private final ReportService reports;
    private final TrainingAiGateway ai;
    private final ObjectMapper json;
    private final TrainingRules rules;
    private final TransactionTemplate transaction;

    public TrainingWorkflow(CurrentUser currentUser, TrainingSessionRepository sessions, TrainingMessageRepository messages,
            ReportService reports, TrainingAiGateway ai, ObjectMapper json, TrainingProperties properties,
            PlatformTransactionManager manager) {
        this.currentUser = currentUser;
        this.sessions = sessions;
        this.messages = messages;
        this.reports = reports;
        this.ai = ai;
        this.json = json;
        this.rules = properties.toRules();
        this.transaction = new TransactionTemplate(manager);
    }

    public TrainingResponses.Discovery discover(long id, TrainingRequests.Discovery request) {
        long owner = currentUser.id();
        var session = checked(id, owner, request, TrainingPhase.DISCOVERY);
        if (session.getDiscoveryTurnCount() >= MAX_DISCOVERY_TURNS) {
            throw error(ErrorCode.INVALID_TRAINING_PHASE);
        }
        String content = safeInput(request.content());
        ObjectNode facts = facts(session);
        int count = session.getDiscoveryTurnCount() + 1;
        String question;
        boolean ready;
        if (ai.isMock()) {
            facts.put("activity", TYPE);
            ready = count >= 3;
            question = ready ? "답변을 바탕으로 가상 훈련을 만들 수 있어요."
                    : count == 1 ? "상대가 어떤 요청을 했나요?" : "그 요청을 받고 어떻게 대응하셨나요?";
        } else {
            var response = ai.request("/discovery/next", Map.of("conversation", conversation(id),
                    "knownFacts", facts, "currentInput", content));
            if (!response.path("facts").isObject() || !response.path("canGenerateScenario").isBoolean()) {
                throw error(ErrorCode.AI_SERVICE_UNAVAILABLE);
            }
            facts.setAll((ObjectNode) response.path("facts"));
            safeGenerated(facts.toString(), 16000);
            ready = response.path("canGenerateScenario").asBoolean() || count == MAX_DISCOVERY_TURNS;
            question = ready ? "답변을 바탕으로 가상 훈련을 만들 수 있어요."
                    : safeGenerated(response.path("nextQuestion").asString(), 1000);
        }
        String reply = question;
        boolean canGenerate = ready;
        facts.put("canGenerateScenario", ready);
        return write(id, owner, request, TrainingPhase.DISCOVERY, locked -> {
            Instant now = Instant.now();
            int sequence = nextSequence(id);
            messages.save(TrainingMessage.fromUser(id, TrainingPhase.DISCOVERY, sequence, content, null,
                    request.requestId(), now));
            messages.save(TrainingMessage.fromAi(id, TrainingPhase.DISCOVERY, sequence + 1, reply, now));
            locked.recordDiscovery(json.writeValueAsString(facts), rules, now);
            sessions.flush();
            return new TrainingResponses.Discovery(first(reply), count, canGenerate, locked.getStateVersion());
        });
    }

    public TrainingResponses.Ocr ocr(long id, List<MultipartFile> images) {
        checkPhase(owned(id, currentUser.id()), TrainingPhase.DISCOVERY);
        if (images.isEmpty()) throw error(ErrorCode.INVALID_REQUEST);
        if (images.size() > 3) throw error(ErrorCode.FILE_TOO_LARGE);
        var multipart = new MultipartBodyBuilder();
        for (MultipartFile image : images) {
            if (image.getSize() > MAX_IMAGE_BYTES) throw error(ErrorCode.FILE_TOO_LARGE);
            try {
                byte[] bytes = image.getBytes();
                MediaType type = imageType(bytes);
                multipart.part("images", new ByteArrayResource(bytes)).contentType(type)
                        .filename(type.equals(MediaType.IMAGE_PNG) ? "image.png" : "image.jpg");
            } catch (IOException e) {
                throw error(ErrorCode.OCR_FAILED);
            }
        }
        if (ai.isMock()) return new TrainingResponses.Ocr("", false);
        JsonNode response;
        try {
            response = ai.request("/ocr/extract", multipart.build());
        } catch (ApiException e) {
            throw error(ErrorCode.OCR_FAILED);
        }
        if (!response.path("usable").asBoolean()) return new TrainingResponses.Ocr("", false);
        String text = response.path("recognizedText").asString("");
        if (text.isBlank() || text.length() > 1000 || SENSITIVE.matcher(text).find() || LIVE_LINK.matcher(text).find()) {
            throw error(ErrorCode.OCR_FAILED);
        }
        return new TrainingResponses.Ocr(text, true);
    }

    public TrainingResponses.Scenario scenario(long id, TrainingRequests.Versioned request) {
        long owner = currentUser.id();
        var session = checked(id, owner, request, TrainingPhase.DISCOVERY);
        var known = facts(session);
        if (!known.path("canGenerateScenario").asBoolean()) throw error(ErrorCode.INVALID_TRAINING_PHASE);
        known.remove("canGenerateScenario");
        JsonNode raw;
        String template;
        if (ai.isMock()) {
            raw = json.createObjectNode().put("title", "중고거래 안전 확인 훈련")
                    .put("description", "가상 거래에서 의심스러운 요청에 대응해 봐요.");
            template = BANK_NOTICE.templateCode();
        } else {
            var result = ai.request("/scenarios/generate", Map.of("facts", known, "fraudTypeCode", TYPE));
            raw = result.path("scenario");
            template = result.path("mockPageTemplateCode").asString("");
        }
        validateScenarioContent(raw);
        var summary = summary(raw);
        String templateCode = BANK_NOTICE.templateCode().equals(template) ? template : null;
        return write(id, owner, request, TrainingPhase.DISCOVERY, locked -> {
            locked.prepareScenario(TYPE, json.writeValueAsString(raw), templateCode, rules, Instant.now());
            sessions.flush();
            return new TrainingResponses.Scenario(id, "SCENARIO_READY", TYPE, summary, templateCode, locked.getStateVersion());
        });
    }

    public TrainingResponses.Started start(long id, TrainingRequests.Versioned request) {
        return write(id, currentUser.id(), request, TrainingPhase.SCENARIO_READY, session -> {
            String opening = scenarioJson(session).path("openingMessage").asString(ReviewedTexts.SCENARIO_OPENING);
            opening = safeGenerated(opening, 1000);
            Instant now = Instant.now();
            session.startPreparedScenario(rules, now);
            var message = TrainingMessage.fromAi(id, TrainingPhase.SIMULATION, nextSequence(id), opening, now);
            var options = optionsFor(opening);
            message.recordOptions(json.writeValueAsString(options));
            messages.save(message);
            sessions.flush();
            return new TrainingResponses.Started("SIMULATION", first(opening), session.getStateVersion(), options);
        });
    }

    public TrainingResponses.Message message(long id, TrainingRequests.Message request) {
        long owner = currentUser.id();
        var session = checked(id, owner, request, TrainingPhase.SIMULATION);
        if (session.isFinishSuggested() || session.getSimulationTurnCount() >= rules.maxSimulationTurns()) {
            throw error(ErrorCode.INVALID_TRAINING_PHASE);
        }
        var choice = request.inputKind() == TrainingRequests.MessageKind.CHOICE ? choice(id, request.selectedCode()) : null;
        if ((choice != null && request.content() != null)
                || (request.inputKind() == TrainingRequests.MessageKind.TEXT && request.selectedCode() != null)) {
            throw error(ErrorCode.INVALID_REQUEST);
        }
        var photo = choice == null ? null : MockPhoto.fromCode(choice.code());
        String content = photo != null ? photo.eventText()
                : simulationInput(choice != null ? choice.label() : request.content());
        int count = session.getSimulationTurnCount() + 1;
        boolean keyScene = !session.isKeySceneShown() && count >= rules.keySceneDeadlineTurn();
        var suggestion = suggest(session, content, count, choice);
        var result = photo == null ? suggestion : photoDisclosure(suggestion, photo);
        boolean finish = result.shouldFinish() || count >= rules.maxSimulationTurns();
        var page = availablePage(session);
        boolean showPage = page != null && !session.isKeySceneShown() && (result.showPage() || keyScene) && !finish;
        boolean revealKeyScene = !session.isKeySceneShown() && (showPage || (keyScene && !finish));
        String reply = finish ? ReviewedTexts.FINISHED
                : keyScene ? "거래 내용을 다시 확인해 주세요. 훈련용 확인 화면에서 의심스러운 점을 살펴볼 수 있어요."
                : result.reply();
        var options = finish ? List.<TrainingResponses.Option>of() : optionsFor(reply);
        return write(id, owner, request, TrainingPhase.SIMULATION, locked -> {
            Instant now = Instant.now();
            int sequence = nextSequence(id);
            var input = choice != null ? TrainingMessage.selected(id, sequence, content, choice.code(), request.requestId(), now)
                    : TrainingMessage.fromUser(id, TrainingPhase.SIMULATION, sequence, content, null, request.requestId(), now);
            input.recordEvaluation(json.writeValueAsString(result.actions()));
            messages.save(input);
            var answer = TrainingMessage.fromAi(id, TrainingPhase.SIMULATION, sequence + 1, reply, now);
            answer.recordOptions(json.writeValueAsString(options));
            messages.save(answer);
            locked.recordSimulationTurn(revealKeyScene, finish, rules, now);
            sessions.flush();
            return new TrainingResponses.Message(new TrainingResponses.Reply(MessageSender.AI, reply, options),
                    result.actions(), count, finish, showPage ? page : null, locked.getStateVersion());
        });
    }

    public TrainingResponses.MockPage mockPage(long id) {
        var session = owned(id, currentUser.id());
        checkPhase(session, TrainingPhase.SIMULATION);
        return requirePage(session);
    }

    public TrainingResponses.Action action(long id, TrainingRequests.Action request) {
        return write(id, currentUser.id(), request, TrainingPhase.SIMULATION, session -> {
            var page = requirePage(session);
            var meta = request.actionMeta();
            if (meta != null && (!page.templateCode().equals(meta.templateCode())
                    || !page.screenCode().equals(meta.screenCode()))) throw error(ErrorCode.INVALID_REQUEST);
            Instant now = Instant.now();
            var safeMeta = new TrainingRequests.ActionMeta(page.templateCode(), page.screenCode());
            messages.save(TrainingMessage.action(id, nextSequence(id), request.actionCode().name(),
                    json.writeValueAsString(safeMeta), request.requestId(), now));
            session.recordActivity(rules, now);
            sessions.flush();
            return new TrainingResponses.Action(true, session.getStateVersion());
        });
    }

    public TrainingResponses.Finished finish(long id, TrainingRequests.Finish request) {
        long owner = currentUser.id();
        var session = owned(id, owner);
        if (session.getStatus() == TrainingStatus.COMPLETED) return finished(id, reports.existingReportId(id));
        check(session, request, TrainingPhase.SIMULATION);
        var events = messages.findByTrainingIdOrderBySequenceNoAsc(id).stream()
                .filter(m -> m.getInputKind() == InputKind.ACTION || MockPhoto.fromCode(m.getActionCode()) != null)
                .map(TrainingMessage::getActionCode).toList();
        ReportEvaluation evaluation = ai.isMock() ? null : evaluate(id, session, events);
        return transaction.execute(status -> {
            var locked = sessions.lockOwned(id, owner).orElseThrow(() -> error(ErrorCode.TRAINING_NOT_FOUND));
            if (locked.getStatus() == TrainingStatus.COMPLETED) return finished(id, reports.existingReportId(id));
            check(locked, request, TrainingPhase.SIMULATION);
            Instant now = Instant.now();
            locked.complete(now);
            boolean verified = events.contains("OFFICIAL_CHANNEL_CHECK")
                    || messages.findByTrainingIdOrderBySequenceNoAsc(id).stream()
                        .anyMatch(m -> m.getTurnEvaluationJson() != null
                                && m.getTurnEvaluationJson().contains("\"OFFICIAL_CHANNEL_CHECK\""));
            var checkedEvaluation = photoFeedback(evaluation, events, verified);
            long reportId = checkedEvaluation != null ? reports.recordEvaluation(locked, checkedEvaluation, now)
                    : reports.recordCompletion(locked, verified,
                        new ReportEvaluation.MockPageEvaluation(events.contains("MOCK_PAGE_OPEN"),
                            events.contains("MOCK_LOGIN_ATTEMPT"), verified), now);
            return finished(id, reportId);
        });
    }

    private ReportEvaluation evaluate(long id, TrainingSession session, List<String> events) {
        var response = ai.request("/training/evaluate", Map.of("scenario", scenarioJson(session),
                "conversation", conversation(id), "actions", events.stream().map(code -> Map.of("actionCode", code)).toList()));
        for (String field : List.of("strengths", "improvements", "turnFeedback", "recommendations")) {
            if (!response.path(field).isArray()) throw error(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        if (!response.path("mockPageEvaluation").isObject()) throw error(ErrorCode.AI_SERVICE_UNAVAILABLE);
        safeGenerated(response.path("overallFeedback").asString(), 4000);
        safeGenerated(response.toString(), 50000);
        try {
            return json.treeToValue(response, ReportEvaluation.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw error(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
    }

    private Suggestion suggest(TrainingSession session, String content, int count, TrainingResponses.Option choice) {
        if (ai.isMock()) {
            boolean verify = (choice != null && "OFFICIAL_CHANNEL_CHECK".equals(choice.code()))
                    || content.contains("공식") && content.contains("확인");
            boolean stop = (choice != null && "END_CONTACT".equals(choice.code())) || content.contains("연락을 끊");
            return new Suggestion("어떻게 확인할 건지 말씀해 주시겠어요?",
                    verify ? List.of("OFFICIAL_CHANNEL_CHECK") : stop ? List.of("END_CONTACT") : List.of(),
                    stop || verify, count == 2);
        }
        var response = ai.request("/simulation/reply", Map.of("scenario", scenarioJson(session),
                "messages", conversation(session.getId()).stream().filter(m -> "SIMULATION".equals(m.get("phase"))).toList(),
                "userInput", content, "turnCount", count));
        if (!response.path("recognizedActions").isArray() || !response.path("shouldFinish").isBoolean()) {
            throw error(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        var actions = new java.util.ArrayList<String>();
        for (var item : response.path("recognizedActions")) {
            String value = item.asString("");
            if (!value.matches("[A-Z][A-Z_0-9]{0,49}") || actions.size() >= 20) {
                throw error(ErrorCode.AI_SERVICE_UNAVAILABLE);
            }
            actions.add(value);
        }
        return new Suggestion(safeGenerated(response.path("reply").asString(), 1000), List.copyOf(actions),
                response.path("shouldFinish").asBoolean(), response.path("mockPage").isObject());
    }

    static List<TrainingResponses.Option> optionsFor(String reply) {
        var options = new java.util.ArrayList<>(SAFE_OPTIONS);
        MockPhoto.offered(reply).forEach(photo -> options.add(photo.option()));
        return List.copyOf(options);
    }

    private static Suggestion photoDisclosure(Suggestion suggestion, MockPhoto photo) {
        var actions = new java.util.LinkedHashSet<>(suggestion.actions());
        actions.remove("SAFE_RESPONSE");
        actions.add("RISKY_RESPONSE");
        actions.add("SENSITIVE_INFO_SHARED");
        actions.add(photo.code());
        return new Suggestion(suggestion.reply(), List.copyOf(actions), suggestion.shouldFinish(), suggestion.showPage());
    }

    static ReportEvaluation photoFeedback(ReportEvaluation evaluation, List<String> events, boolean verified) {
        var photos = events.stream().map(MockPhoto::fromCode).filter(java.util.Objects::nonNull).distinct().toList();
        if (photos.isEmpty()) return evaluation;
        var improvements = new java.util.ArrayList<String>();
        photos.forEach(photo -> improvements.add(photo.subject() + " 제공 요청에 응했어요. 실제 상황에서는 출처를 확인하기 전 자료를 보내지 마세요."));
        if (evaluation != null) improvements.addAll(evaluation.improvements());
        return new ReportEvaluation(evaluation == null ? "사진 제공 요청에 응한 행동을 되돌아보세요." : evaluation.overallFeedback(),
                evaluation == null ? List.of() : evaluation.strengths(), improvements.stream().distinct().limit(10).toList(),
                evaluation == null ? List.of() : evaluation.turnFeedback(),
                evaluation == null ? new ReportEvaluation.MockPageEvaluation(events.contains("MOCK_PAGE_OPEN"),
                        events.contains("MOCK_LOGIN_ATTEMPT"), verified) : evaluation.mockPageEvaluation(),
                evaluation == null ? List.of("개인 자료를 요구받으면 직접 찾은 공식 채널에서 요청을 확인하세요.") : evaluation.recommendations());
    }

    private record Suggestion(String reply, List<String> actions, boolean shouldFinish, boolean showPage) {}

    private List<Map<String, String>> conversation(long id) {
        return messages.findByTrainingIdOrderBySequenceNoAsc(id).stream()
                .filter(m -> m.getContent() != null && !SENSITIVE.matcher(m.getContent()).find()
                        && !LIVE_LINK.matcher(m.getContent()).find())
                .map(m -> Map.of("sender", m.getSender().name(), "phase", TrainingResponses.phase(m.getPhase()),
                        "content", m.getContent())).toList();
    }

    private TrainingResponses.Option choice(long id, String code) {
        if (code == null || code.isBlank()) throw error(ErrorCode.INVALID_REQUEST);
        var last = messages.findByTrainingIdOrderBySequenceNoAsc(id).reversed().stream()
                .filter(m -> m.getPhase() == TrainingPhase.SIMULATION && m.getSender() == MessageSender.AI)
                .findFirst().orElseThrow(() -> error(ErrorCode.INVALID_REQUEST));
        if (last.getOptionsJson() != null) {
            for (var option : json.readTree(last.getOptionsJson())) {
                if (code.equals(option.path("code").asString())) {
                    var photo = MockPhoto.fromCode(code);
                    return photo == null ? new TrainingResponses.Option(code, option.path("label").asString()) : photo.option();
                }
            }
        }
        throw error(ErrorCode.INVALID_REQUEST);
    }

    private TrainingSession checked(long id, long owner, TrainingRequests.Mutation request, TrainingPhase phase) {
        var session = owned(id, owner);
        check(session, request, phase);
        return session;
    }

    private TrainingSession owned(long id, long owner) {
        return sessions.findByIdAndUserId(id, owner).orElseThrow(() -> error(ErrorCode.TRAINING_NOT_FOUND));
    }

    private void check(TrainingSession session, TrainingRequests.Mutation request, TrainingPhase phase) {
        if (messages.existsByTrainingIdAndClientRequestId(session.getId(), request.requestId())) {
            throw error(ErrorCode.DUPLICATE_REQUEST);
        }
        if (session.getStateVersion() != request.stateVersion()) throw error(ErrorCode.STATE_VERSION_CONFLICT);
        checkPhase(session, phase);
    }

    private static void checkPhase(TrainingSession session, TrainingPhase phase) {
        if (session.isExpired(Instant.now())) throw error(ErrorCode.SESSION_EXPIRED);
        if (session.getStatus() != TrainingStatus.ACTIVE || session.getPhase() != phase) {
            throw error(ErrorCode.INVALID_TRAINING_PHASE);
        }
    }

    private <T> T write(long id, long owner, TrainingRequests.Mutation request, TrainingPhase phase,
            Function<TrainingSession, T> mutation) {
        return transaction.execute(status -> {
            var session = sessions.lockOwned(id, owner).orElseThrow(() -> error(ErrorCode.TRAINING_NOT_FOUND));
            check(session, request, phase);
            return mutation.apply(session);
        });
    }

    private int nextSequence(long id) { return (int) messages.countByTrainingId(id) + 1; }

    private ObjectNode facts(TrainingSession session) {
        return session.getContextFactsJson() == null ? json.createObjectNode()
                : (ObjectNode) json.readTree(session.getContextFactsJson());
    }

    private JsonNode scenarioJson(TrainingSession session) {
        return session.getScenarioJson() == null ? json.createObjectNode() : json.readTree(session.getScenarioJson());
    }

    static void validateScenarioContent(JsonNode scenario) {
        if (!scenario.isObject() || scenario.toString().length() > 16000) {
            throw error(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        ObjectNode content = ((ObjectNode) scenario).deepCopy();
        // FastAPI resolves these references from its case catalog, not from model-written dialogue.
        // Their source URLs and publication dates are metadata, not simulated phishing links or account numbers.
        content.remove("basedOnCases");
        safeGenerated(content.toString(), 16000);
    }

    static TrainingResponses.ScenarioSummary summary(JsonNode scenario) {
        return new TrainingResponses.ScenarioSummary(safeGenerated(scenario.path("title").asString(), 200),
                safeGenerated(scenario.path("description").asString(), 1000));
    }

    static TrainingResponses.MockPage availablePage(TrainingSession session) {
        return BANK_NOTICE.templateCode().equals(session.getMockPageTemplateCode()) ? BANK_NOTICE : null;
    }

    private static TrainingResponses.MockPage requirePage(TrainingSession session) {
        var page = availablePage(session);
        if (page == null) throw error(ErrorCode.TRAINING_NOT_FOUND);
        return page;
    }

    private static TrainingResponses.FirstMessage first(String content) {
        return new TrainingResponses.FirstMessage(MessageSender.AI, content);
    }

    private static TrainingResponses.Finished finished(long id, long report) {
        return new TrainingResponses.Finished(id, TrainingStatus.COMPLETED, "RESULT", report);
    }

    private static String safeInput(String text) {
        if (text == null || text.isBlank()) throw error(ErrorCode.INVALID_REQUEST);
        if (SENSITIVE.matcher(text).find() || LIVE_LINK.matcher(text).find()) throw error(ErrorCode.SENSITIVE_DATA_DETECTED);
        return text.strip();
    }

    static String simulationInput(String text) {
        if (text == null || text.isBlank() || text.length() > 1000) throw error(ErrorCode.INVALID_REQUEST);
        if (LIVE_LINK.matcher(text).find()) throw error(ErrorCode.SENSITIVE_DATA_DETECTED);
        // Accept disclosure during simulation, but send/store only type markers, never the submitted values.
        text = text.replaceAll("(?<!\\d)\\d{6}[- ]?[1-8]\\d{6}(?!\\d)", "[주민등록번호]")
                .replaceAll("(?<!\\d)(?:\\d{4}[ -]?){3}\\d{4}(?!\\d)", "[카드번호]")
                .replaceAll("(?<!\\d)0\\d{1,2}[- .]?\\d{3,4}[- .]?\\d{4}(?!\\d)", "[전화번호]")
                .replaceAll("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}", "[이메일]")
                .replaceAll("(?i)(?:비밀번호|비번|패스워드|password|pw|PIN)\\s*(?:[:=]|은|는)\\s*\\S+", "[비밀번호]")
                .replaceAll("(?i)(?:인증번호|OTP|CVC|CVV)\\s*(?:[:=]|은|는)?\\s*\\d{3,8}", "[인증정보]")
                .replaceAll("(?<!\\d)\\d{2,6}-\\d{2,6}-\\d{2,8}(?!\\d)|(?<!\\d)(?:\\d[ -]?){9,18}\\d(?!\\d)", "[계좌번호]");
        return SENSITIVE.matcher(text).replaceAll("[민감정보]").strip();
    }

    private static String safeGenerated(String text, int limit) {
        if (text == null || text.isBlank() || text.length() > limit
                || SENSITIVE.matcher(text).find() || LIVE_LINK.matcher(text).find()) {
            throw error(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        return text;
    }

    private static MediaType imageType(byte[] data) {
        if (data.length >= 8 && (data[0] & 255) == 137 && data[1] == 80 && data[2] == 78 && data[3] == 71
                && data[4] == 13 && data[5] == 10 && data[6] == 26 && data[7] == 10) return MediaType.IMAGE_PNG;
        if (data.length >= 3 && (data[0] & 255) == 255 && (data[1] & 255) == 216 && (data[2] & 255) == 255) {
            return MediaType.IMAGE_JPEG;
        }
        throw error(ErrorCode.INVALID_REQUEST);
    }

    private static ApiException error(ErrorCode code) { return new ApiException(code); }
}
