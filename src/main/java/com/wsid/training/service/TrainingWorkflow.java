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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class TrainingWorkflow {
    /**
     * 기본 사기 유형. AI 시나리오 생성(AI-03) 계약이 아직 이 유형만 받아서 AI 요청에는 이 값을 보내요.
     * 저장·응답·리포트에 쓰는 유형은 만들어진 시나리오 내용으로 다시 정해요 (fraudTypeOf).
     */
    private static final String TYPE = "SECONDHAND_TRADE";
    private static final int MAX_DISCOVERY_TURNS = 5;
    private static final List<TrainingResponses.Option> SAFE_OPTIONS = List.of(
            new TrainingResponses.Option("OFFICIAL_CHANNEL_CHECK", "공식 채널에서 직접 확인할게요."),
            new TrainingResponses.Option("END_CONTACT", "대화를 중단할게요."));
    private static final TrainingResponses.MockPage BANK_NOTICE = TrainingResponses.MockPage.bankNotice();
    private static final String IDENTIFIERS =
            "[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|(?<!\\d)0\\d{1,2}[- .]?[0-9]{3,4}[- .]?[0-9]{4}(?!\\d)"
            + "|(?<!\\d)\\d{6}[- ]?[1-8]\\d{6}(?!\\d)|(?<!\\d)\\d{2,6}-\\d{2,6}-\\d{2,8}(?!\\d)"
            + "|(?<!\\d)(?:\\d[ -]?){9,18}\\d(?!\\d)";
    private static final String SECRET_LABEL = "(?i)(?:비밀번호|인증번호|OTP|password|PIN)\\s*(?:[:=]|은|는)\\s*";
    private static final Pattern SENSITIVE = Pattern.compile(IDENTIFIERS + "|" + SECRET_LABEL + "\\S+|^\\s*\\d{4,8}\\s*$");
    // Advice such as "비밀번호는 공유하지 마세요" contains no credential value.
    private static final Pattern GENERATED_SENSITIVE = Pattern.compile(IDENTIFIERS + "|" + SECRET_LABEL
            + "[A-Za-z0-9!@#$%^&*_.+-]{4,}|^\\s*\\d{4,8}\\s*$");
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
        String fraudType = fraudTypeOf(raw); // 택배 시나리오면 SMISHING, 채용이면 JOB_SCAM ...
        return write(id, owner, request, TrainingPhase.DISCOVERY, locked -> {
            locked.prepareScenario(fraudType, json.writeValueAsString(raw), templateCode, rules, Instant.now());
            sessions.flush();
            return new TrainingResponses.Scenario(id, "SCENARIO_READY", fraudType, summary, templateCode, locked.getStateVersion());
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
            return new TrainingResponses.Started("SIMULATION", first(opening), session.getStateVersion(), options,
                    containsMockLink(opening) ? availablePage(session) : null);
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
        boolean pageSubmitted = messages.findByTrainingIdOrderBySequenceNoAsc(id).stream()
                .anyMatch(message -> message.getInputKind() == InputKind.ACTION && "MOCK_LOGIN_ATTEMPT".equals(message.getActionCode()));
        boolean keyScene = !pageSubmitted && !session.isKeySceneShown() && count >= rules.keySceneDeadlineTurn();
        var suggestion = suggest(session, content, count, choice);
        var result = photo == null ? suggestion : photoDisclosure(suggestion, photo);
        boolean finish = result.shouldFinish() || count >= rules.maxSimulationTurns();
        var page = availablePage(session);
        boolean showPage = !pageSubmitted && page != null && (result.showPage() || containsMockLink(result.reply()) || keyScene) && !finish;
        boolean revealKeyScene = !session.isKeySceneShown() && (showPage || (keyScene && !finish));
        // AI가 대화를 마무리했으면(사용자가 끊었거나 시나리오 완료) 사기범의 마지막 한마디를 그대로 보여 주고,
        // 최대 턴에 닿아 강제로 끝날 때만 안내 문구를 써요. 종료 안내는 프론트의 결과 카드가 이어서 보여 줘요.
        String reply = finish ? (result.shouldFinish() && result.reply() != null && !result.reply().isBlank()
                        ? result.reply() : ReviewedTexts.FINISHED)
                : keyScene ? "거래 내용을 다시 확인해 주세요. 훈련용 확인 화면에서 의심스러운 점을 살펴볼 수 있어요."
                : result.reply();
        var options = finish ? List.<TrainingResponses.Option>of() : optionsFor(reply);
        return write(id, owner, request, TrainingPhase.SIMULATION, locked -> {
            Instant now = Instant.now();
            int sequence = nextSequence(id);
            // 이름·주소처럼 규칙으로 못 가리는 정보는 AI가 가린 문장(maskedInput)으로 바꿔서 저장해요 (원문은 DB에 남기지 않음)
            String masked = choice == null ? result.maskedInput() : null;
            String stored = masked != null ? masked : content;
            var input = choice != null ? TrainingMessage.selected(id, sequence, content, choice.code(), request.requestId(), now)
                    : TrainingMessage.fromUser(id, TrainingPhase.SIMULATION, sequence, stored, null, request.requestId(), now);
            input.recordEvaluation(json.writeValueAsString(result.actions()));
            messages.save(input);
            var answer = TrainingMessage.fromAi(id, TrainingPhase.SIMULATION, sequence + 1, reply, now);
            answer.recordOptions(json.writeValueAsString(options));
            messages.save(answer);
            if (result.nextScene() != null && locked.getScenarioJson() != null) {
                ObjectNode progress = ((ObjectNode) scenarioJson(locked)).deepCopy();
                progress.put("currentScene", result.nextScene());
                locked.recordScenarioProgress(json.writeValueAsString(progress));
            }
            locked.recordSimulationTurn(revealKeyScene, finish, rules, now);
            sessions.flush();
            return new TrainingResponses.Message(new TrainingResponses.Reply(MessageSender.AI, reply, options),
                    result.actions(), count, finish, showPage ? page : null, locked.getStateVersion(), masked);
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
        var scenario = scenarioJson(session);
        var payload = new java.util.HashMap<String, Object>();
        payload.put("scenario", scenario);
        payload.put("messages", conversation(session.getId()).stream().filter(m -> "SIMULATION".equals(m.get("phase"))).toList());
        payload.put("userInput", content);
        payload.put("turnCount", count);
        if (scenario.path("currentScene").isNumber()) payload.put("currentScene", scenario.path("currentScene").asInt());
        var response = ai.request("/simulation/reply", payload);
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
        Integer nextScene = null;
        if (response.path("nextScene").isNumber()) {
            nextScene = response.path("nextScene").asInt();
            if (nextScene < 1 || nextScene > Math.max(1, scenario.path("keyScenes").size())) throw error(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        return new Suggestion(safeGenerated(response.path("reply").asString(), 1000), List.copyOf(actions),
                response.path("shouldFinish").asBoolean(), response.path("mockPage").isObject(), nextScene,
                maskedInputOf(response.path("maskedInput")));
    }

    /** AI가 돌려준 가린 문장을 한 번 더 숫자 패턴으로 가려서 써요. 이상하면(비었거나 길거나 링크 포함) 쓰지 않아요 */
    static String maskedInputOf(JsonNode node) {
        if (node == null || !node.isString()) return null;
        String text = node.asString("").strip();
        if (text.isEmpty() || text.length() > 300) return null;
        try {
            return simulationInput(text);
        } catch (ApiException e) {
            return null;
        }
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
        return new Suggestion(suggestion.reply(), List.copyOf(actions), suggestion.shouldFinish(), suggestion.showPage(), suggestion.nextScene());
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

    /** maskedInput: AI가 이름·주소 등을 가린 사용자 문장. 있으면 원문 대신 저장하고 화면에도 이 값을 보여 줘요 */
    private record Suggestion(String reply, List<String> actions, boolean shouldFinish, boolean showPage, Integer nextScene,
            String maskedInput) {
        private Suggestion(String reply, List<String> actions, boolean shouldFinish, boolean showPage, Integer nextScene) {
            this(reply, actions, shouldFinish, showPage, nextScene, null);
        }
        private Suggestion(String reply, List<String> actions, boolean shouldFinish, boolean showPage) {
            this(reply, actions, shouldFinish, showPage, null, null);
        }
    }

    private List<Map<String, String>> conversation(long id) {
        var history = new java.util.ArrayList<Map<String, String>>();
        var emitted = new java.util.HashSet<String>();
        for (var message : messages.findByTrainingIdOrderBySequenceNoAsc(id)) {
            if (message.getInputKind() == InputKind.ACTION) {
                String event = switch (message.getActionCode()) {
                    case "MOCK_LOGIN_ATTEMPT" -> "[훈련 행동: 본인 확인 정보 제출 완료]";
                    case "OFFICIAL_CHANNEL_CHECK" -> "[훈련 행동: 공식 경로 확인]";
                    default -> null;
                };
                if (event != null && emitted.add(message.getActionCode())) {
                    history.add(Map.of("sender", "SYSTEM", "phase", "SIMULATION", "content", event));
                }
                continue;
            }
            String text = message.getContent();
            if (text != null && !SENSITIVE.matcher(text).find() && !LIVE_LINK.matcher(text).find()) {
                history.add(Map.of("sender", message.getSender().name(), "phase", TrainingResponses.phase(message.getPhase()), "content", text));
            }
        }
        return List.copyOf(history);
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
        var persona = scenario.path("persona");
        String organization = actorLabel(persona.path("organization").asString(""));
        if (List.of("없음", "개인", "해당 없음").contains(organization)) organization = "";
        String role = actorLabel(persona.path("role").asString(""));
        String name = actorLabel(persona.path("name").asString(""));
        String sender = !organization.isBlank()
                ? (role.contains(organization) ? role : (organization + " " + role).strip())
                : (!name.isBlank() ? name : "상대방");
        return new TrainingResponses.ScenarioSummary(safeGenerated(scenario.path("title").asString(), 200),
                safeGenerated(scenario.path("description").asString(), 1000), safeGenerated(sender, 200),
                organization.isBlank() ? null : safeGenerated(organization, 100));
    }

    private static String actorLabel(String value) {
        return value.replace("(가상)", "").replace("(사칭)", "").replace("사칭", "")
                .replaceAll("\\s+", " ").strip();
    }

    /** 시나리오의 상대 역할·기관·제목·설명으로 모의 페이지 템플릿을 골라요 */
    static MockPageTemplates.Template templateOf(JsonNode scenario) {
        var persona = scenario.path("persona");
        return MockPageTemplates.pick(persona.path("role").asString(""), persona.path("organization").asString(""),
                scenario.path("title").asString(""), scenario.path("description").asString(""));
    }

    /** 시나리오 내용으로 사기 유형을 정해요. 정하지 못하면 기본 유형(TYPE) */
    static String fraudTypeOf(JsonNode scenario) {
        String code = templateOf(scenario).fraudTypeCode();
        return code == null ? TYPE : code;
    }

    static boolean containsMockLink(String text) {
        return text != null && Pattern.compile("\\[모의\\s*링크\\]").matcher(text).find();
    }

    static TrainingResponses.MockPage availablePage(TrainingSession session) {
        JsonNode scenario = session.getScenarioJson() == null ? null : new ObjectMapper().readTree(session.getScenarioJson());
        // Every stored scenario gets a fixed local template chosen from its own words (MockPageTemplates).
        // Model-generated URLs or page HTML are never accepted.
        if (scenario == null) return BANK_NOTICE.templateCode().equals(session.getMockPageTemplateCode()) ? BANK_NOTICE : null;
        String organization = actorLabel(scenario.path("persona").path("organization").asString(""));
        // 사기 유형에 맞는 화면 종류 (택배 → DELIVERY_ADDRESS, 채용 → RECRUIT_APPLICANT ...)
        var template = templateOf(scenario);
        return new TrainingResponses.MockPage(template.code(), template.title(),
                organization.isBlank() ? BANK_NOTICE.institutionName() : safeGenerated(organization, 100),
                template.screenCode(), true);
    }

    private static TrainingResponses.MockPage requirePage(TrainingSession session) {
        var page = availablePage(session);
        if (page == null) throw error(ErrorCode.MOCK_PAGE_NOT_FOUND);
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

    static String safeGenerated(String text, int limit) {
        if (text == null || text.isBlank() || text.length() > limit
                || GENERATED_SENSITIVE.matcher(text).find() || LIVE_LINK.matcher(text).find()) {
            throw error(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        return text;
    }

    private static ApiException error(ErrorCode code) { return new ApiException(code); }
}
