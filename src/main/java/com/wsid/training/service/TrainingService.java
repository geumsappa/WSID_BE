package com.wsid.training.service;

import com.wsid.ai.AiClient;
import com.wsid.ai.AiUnavailableException;
import com.wsid.ai.dto.TrainingTurnRequest;
import com.wsid.ai.dto.TrainingTurnSuggestion;
import com.wsid.auth.CurrentUser;
import com.wsid.common.domain.MessageSender;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.training.domain.TextSource;
import com.wsid.training.domain.TrainingMessage;
import com.wsid.training.domain.TrainingPhase;
import com.wsid.training.domain.TrainingRules;
import com.wsid.training.domain.TrainingSession;
import com.wsid.training.domain.TrainingStatus;
import com.wsid.training.domain.TurnInput;
import com.wsid.training.domain.TurnOutcome;
import com.wsid.training.domain.UserAction;
import com.wsid.training.dto.MessageDto;
import com.wsid.training.dto.SessionStateResponse;
import com.wsid.training.dto.SubmitTurnRequest;
import com.wsid.training.dto.TurnResponse;
import com.wsid.training.repository.TrainingMessageRepository;
import com.wsid.training.repository.TrainingSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import com.wsid.ai.dto.DiscoveryRequest;
import com.wsid.ai.dto.DiscoveryResult;
import com.wsid.ai.dto.DiscoveryTurn;
import com.wsid.training.domain.*;
import com.wsid.training.dto.DiscoveryResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 훈련 흐름을 조율해요. 규칙 판단은 TrainingSession(domain)에 맡기고,
 * 여기서는 "읽기 → AI 호출 → 저장" 순서와 트랜잭션 경계만 관리해요.
 */
@Service
public class TrainingService {

    private static final Logger log = LoggerFactory.getLogger(TrainingService.class);

    // TODO(SIM-004, SIM-005): 일상 대화에서 파악한 사실로 유형을 고르고 시나리오를 생성해요.
    private static final String DEFAULT_FRAUD_TYPE_CODE = "USED_GOODS_TRADE";

    private final TrainingSessionRepository sessions;
    private final TrainingMessageRepository messages;
    private final AiClient aiClient;
    private final CurrentUser currentUser;
    private final TransactionTemplate transaction;
    private final TrainingRules rules;

    public TrainingService(
            TrainingSessionRepository sessions,
            TrainingMessageRepository messages,
            AiClient aiClient,
            CurrentUser currentUser,
            PlatformTransactionManager transactionManager,
            TrainingProperties properties) {
        this.sessions = sessions;
        this.messages = messages;
        this.aiClient = aiClient;
        this.currentUser = currentUser;
        this.transaction = new TransactionTemplate(transactionManager);
        this.rules = properties.toRules();
    }

    /** SIM-001: 새 훈련을 만들어 소유자를 연결하고, 준비된 첫 질문을 돌려줘요. */
    public TurnResponse start() {
        long userId = currentUser.id();
        return transaction.execute(tx -> {
            Instant now = Instant.now();
            TrainingSession session = sessions.saveAndFlush(TrainingSession.start(userId, rules, now));
            messages.save(TrainingMessage.fromAi(
                    session.getId(), session.getPhase(), 1, ReviewedTexts.DISCOVERY_FIRST_QUESTION, now));
            MessageDto first = MessageDto.reviewed(MessageSender.AI, ReviewedTexts.DISCOVERY_FIRST_QUESTION);
            return TurnResponse.of(session, first, false, false);
        });
    }

    /** 홈의 '이어서 하기': 진행 중인 훈련이 있으면 돌려줘요. */
    public Optional<SessionStateResponse> findActive() {
        long userId = currentUser.id();
        return sessions.findFirstByUserIdAndStatusOrderByLastActivityAtDesc(userId, TrainingStatus.ACTIVE)
                .filter(session -> !session.isExpired(Instant.now()))
                .map(this::toState);
    }

    /** 훈련 재개: 마지막 진행 단계와 지난 대화를 돌려줘요. */
    public SessionStateResponse getState(long sessionId) {
        return toState(load(sessionId, currentUser.id()));
    }

    /** TODO(경로 전환): 프론트가 새 경로로 옮기면 이전용 변환을 제거해요. */
    public TurnResponse submitTurn(long sessionId, SubmitTurnRequest request) {
        TrainingSession snapshot = load(sessionId, currentUser.id());
        if (snapshot.getPhase() != TrainingPhase.DISCOVERY) return submitSimulationMessage(sessionId, request);
        DiscoveryResponse response = submitDiscovery(sessionId, request);
        return new TurnResponse(response.trainingId(), response.stateVersion(), response.phase(),
                response.phase() == TrainingPhase.SIMULATION ? 1 : 0,
                response.messages().getLast(), false, false);
    }

    public TurnResponse submitSimulationMessage(long sessionId, SubmitTurnRequest request) {
        long userId = currentUser.id();
        TrainingSession snapshot = load(sessionId, userId);
        requireRequest(snapshot, request);
        requirePhase(snapshot, TrainingPhase.SIMULATION);
        TrainingTurnSuggestion suggestion = askAi(snapshot, request.message());
        return write(sessionId, userId, request, session -> {
            requirePhase(session, TrainingPhase.SIMULATION);
            return applyTurn(session, request, suggestion);
        });
    }

    public DiscoveryResponse submitDiscovery(long sessionId, SubmitTurnRequest request) {
        long userId = currentUser.id();
        TrainingSession snapshot = load(sessionId, userId);
        requireRequest(snapshot, request);
        requireDiscovery(snapshot);
        ContextFacts facts = snapshot.getContextFacts();
        List<DiscoveryTurn> history = messages.findByTrainingIdAndPhaseOrderBySequenceNoAsc(sessionId, TrainingPhase.DISCOVERY)
                .stream().map(m -> new DiscoveryTurn(m.getSender() == MessageSender.USER ? "user" : "assistant", m.getContent()))
                .toList();
        // TODO(개인정보 보호): 민감정보 감지·재입력 안내를 구현하기 전 운영 입력으로 사용하지 않아요.
        DiscoveryResult result;
        try {
            result = aiClient.suggestDiscovery(new DiscoveryRequest(history, facts.values(), request.message(), request.requestId()));
            if (result == null || !result.isValid()) throw new AiUnavailableException("AI-01 응답 형식 오류");
        } catch (AiUnavailableException e) {
            // 예외 메시지/원문/사실 값은 기록하지 않아요.
            log.warn("AI-01 unavailable: trainingId={}, requestId={}", sessionId, request.requestId());
            result = null;
        }
        DiscoveryResult accepted = result;
        return write(sessionId, userId, request, session -> {
            requireDiscovery(session);
            ContextFacts merged = accepted == null ? session.getContextFacts() : session.getContextFacts().merge(accepted.facts());
            DiscoveryOutcome outcome = session.applyDiscoveryAnswer(new DiscoveryInput(accepted != null,
                    accepted != null && Boolean.TRUE.equals(accepted.canGenerateScenario()), merged.hasAll(rules.requiredFacts())), rules, Instant.now());
            session.replaceContextFacts(merged);
            int sequence = nextSequence(sessionId);
            messages.save(TrainingMessage.fromUser(sessionId, TrainingPhase.DISCOVERY, sequence,
                    request.message(), null, request.requestId(), Instant.now()));
            List<MessageDto> response;
            if (outcome.status() == DiscoveryStatus.ASKING) {
                String question = accepted == null ? DiscoveryQuestions.fallbackFor(merged) : accepted.nextQuestion();
                messages.save(TrainingMessage.fromAi(sessionId, TrainingPhase.DISCOVERY, sequence + 1, question, Instant.now()));
                response = List.of(accepted == null ? MessageDto.reviewed(MessageSender.AI, question) : MessageDto.fromAi(question));
            } else {
                String notice = outcome.status() == DiscoveryStatus.DEFAULT_SCENARIO
                        ? ReviewedTexts.DISCOVERY_DEFAULT : ReviewedTexts.DISCOVERY_READY;
                response = startSimulation(session, sequence + 1, notice);
            }
            sessions.saveAndFlush(session);
            return new DiscoveryResponse(sessionId, session.getStateVersion(), session.getPhase(),
                    session.discoveryStatus(), session.questionCount(), accepted == null, response);
        });
    }

    private List<MessageDto> startSimulation(TrainingSession session, int sequence, String notice) {
        Instant now = Instant.now();
        messages.save(TrainingMessage.fromSystem(session.getId(), TrainingPhase.DISCOVERY, sequence, notice, now));
        // TODO(TRN-06, TRN-07): 개인화 시나리오 생성과 시작 API로 교체할 임시 자동 시작이에요.
        session.beginSimulation(DEFAULT_FRAUD_TYPE_CODE, rules, now);
        messages.save(TrainingMessage.fromAi(session.getId(), TrainingPhase.SIMULATION, sequence + 1, ReviewedTexts.SCENARIO_OPENING, now));
        return List.of(MessageDto.reviewed(MessageSender.SYSTEM, notice), MessageDto.reviewed(MessageSender.AI, ReviewedTexts.SCENARIO_OPENING));
    }

    private <T> T write(long id, long userId, SubmitTurnRequest request, Function<TrainingSession, T> action) {
        try {
            return transaction.execute(tx -> {
                TrainingSession session = load(id, userId);
                requireRequest(session, request);
                return action.apply(session);
            });
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            // 트랜잭션이 롤백된 뒤 승리한 동일 요청을 확인해요.
            if (messages.existsByTrainingIdAndClientRequestId(id, request.requestId())) throw new ApiException(ErrorCode.DUPLICATE_REQUEST);
            throw e;
        } catch (ApiException e) {
            if (e.errorCode() == ErrorCode.SESSION_VERSION_CONFLICT && messages.existsByTrainingIdAndClientRequestId(id, request.requestId()))
                throw new ApiException(ErrorCode.DUPLICATE_REQUEST);
            throw e;
        }
    }

    private void requireRequest(TrainingSession session, SubmitTurnRequest request) {
        if (messages.existsByTrainingIdAndClientRequestId(session.getId(), request.requestId())) throw new ApiException(ErrorCode.DUPLICATE_REQUEST);
        requireCurrent(session, request.stateVersion());
    }
    private void requirePhase(TrainingSession session, TrainingPhase phase) {
        if (session.getPhase() != phase) throw new ApiException(ErrorCode.WRONG_PHASE);
    }
    private void requireDiscovery(TrainingSession session) {
        requirePhase(session, TrainingPhase.DISCOVERY);
        if (session.discoveryStatus() != DiscoveryStatus.ASKING) throw new ApiException(ErrorCode.DISCOVERY_COMPLETED);
    }

    private TurnResponse applyTurn(
            TrainingSession session, SubmitTurnRequest request, TrainingTurnSuggestion suggestion) {
        long sessionId = session.getId();
        Instant now = Instant.now();
        int sequence = nextSequence(sessionId);

        // TODO(실제 피해 전환): suggestion.realIncident()이면 훈련을 일시 중지하고 피해 대응 세션으로 넘겨요.
        UserAction action = UserAction.fromCode(suggestion.userAction());
        messages.save(TrainingMessage.fromUser(
                sessionId, session.getPhase(), sequence, request.message(), action, request.requestId(), now));

        TurnInput input = new TurnInput(action, suggestion.suggestKeyScene(), suggestion.replyUsable());
        TurnOutcome outcome = session.applyTurn(input, rules, now);
        MessageDto reply = messageFor(outcome, suggestion);

        TrainingMessage saved = reply.sender() == MessageSender.SYSTEM
                ? TrainingMessage.fromSystem(sessionId, session.getPhase(), sequence + 1, reply.text(), now)
                : TrainingMessage.fromAi(sessionId, session.getPhase(), sequence + 1, reply.text(), now);
        messages.save(saved);
        sessions.saveAndFlush(session);

        // TODO(SIM-010): outcome.finished()이면 평가 결과로 리포트를 만들어 저장해요.
        return TurnResponse.of(session, reply, outcome.keyScene(), outcome.finished());
    }

    private TrainingTurnSuggestion askAi(TrainingSession session, String userMessage) {
        TrainingTurnRequest request = new TrainingTurnRequest(
                session.getFraudTypeCode(),
                session.getPhase().name(),
                session.getSimulationTurnCount(),
                session.isKeySceneShown(),
                userMessage);
        try {
            return aiClient.suggestTrainingTurn(request);
        } catch (AiUnavailableException e) {
            // 사용자 입력은 로그에 남기지 않아요.
            log.warn("AI 호출 실패, 검토된 문구로 이어가요. sessionId={}", session.getId(), e);
            return TrainingTurnSuggestion.unavailable();
        }
    }

    private MessageDto messageFor(TurnOutcome outcome, TrainingTurnSuggestion suggestion) {
        if (outcome.finished()) {
            return MessageDto.reviewed(MessageSender.SYSTEM, ReviewedTexts.FINISHED);
        }
        if (outcome.textSource() == TextSource.AI) {
            return MessageDto.fromAi(suggestion.reply());
        }
        String reviewed = outcome.keyScene() ? ReviewedTexts.KEY_SCENE : ReviewedTexts.FALLBACK_LINE;
        return MessageDto.reviewed(MessageSender.AI, reviewed);
    }

    private SessionStateResponse toState(TrainingSession session) {
        return SessionStateResponse.of(session, messages.findByTrainingIdOrderBySequenceNoAsc(session.getId()));
    }

    /** 소유권 확인을 겸해요. 없는 세션과 다른 사람의 세션 모두 404예요. */
    private TrainingSession load(long sessionId, long userId) {
        return sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
    }

    private void requireCurrent(TrainingSession session, int expectedVersion) {
        if (session.getStatus() == TrainingStatus.COMPLETED) {
            throw new ApiException(ErrorCode.SESSION_FINISHED);
        }
        if (session.isExpired(Instant.now())) {
            // TODO(세션 만료): 만료된 세션의 status를 EXPIRED로 바꾸는 정리 작업을 추가해요.
            throw new ApiException(ErrorCode.SESSION_EXPIRED);
        }
        if (session.getStateVersion() != expectedVersion) {
            throw new ApiException(ErrorCode.SESSION_VERSION_CONFLICT);
        }
    }

    private int nextSequence(long sessionId) {
        return (int) messages.countByTrainingId(sessionId) + 1;
    }
}
