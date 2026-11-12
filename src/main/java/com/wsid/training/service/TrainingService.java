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

    public TurnResponse submitTurn(long sessionId, SubmitTurnRequest request) {
        long userId = currentUser.id();

        // 중복 처리: 이미 반영한 메시지가 다시 오면 두 번 반영하지 않아요.
        // TODO: 409 대신 처음에 돌려줬던 응답을 그대로 다시 돌려주도록 바꿔요.
        if (messages.existsByTrainingIdAndClientRequestId(sessionId, request.requestId())) {
            throw new ApiException(ErrorCode.DUPLICATE_REQUEST);
        }

        TrainingSession snapshot = load(sessionId, userId);
        requireCurrent(snapshot, request.stateVersion());

        // TODO(개인정보 보호): 저장하기 전에 민감정보를 감지하면 삭제 후 다시 입력하도록 안내해요.

        if (snapshot.getPhase() == TrainingPhase.DISCOVERY) {
            return transaction.execute(tx -> finishDiscovery(sessionId, userId, request));
        }

        // AI 호출은 최대 수십 초가 걸려요. DB 커넥션을 잡지 않도록 트랜잭션 밖에서 해요.
        TrainingTurnSuggestion suggestion = askAi(snapshot, request.message());

        return transaction.execute(tx -> applyTurn(sessionId, userId, request, suggestion));
    }

    private TurnResponse finishDiscovery(long sessionId, long userId, SubmitTurnRequest request) {
        // TODO(SIM-001, SIM-002): 답변에서 사실을 추출하고 후속 질문을 최대 5회 이어가요.
        //   지금은 첫 답변을 받으면 바로 기본 유형의 가상 훈련으로 넘어가요.
        TrainingSession session = load(sessionId, userId);
        requireCurrent(session, request.stateVersion());
        Instant now = Instant.now();
        int sequence = nextSequence(sessionId);

        messages.save(TrainingMessage.fromUser(
                sessionId, session.getPhase(), sequence, request.message(), null, request.requestId(), now));

        session.beginSimulation(DEFAULT_FRAUD_TYPE_CODE, rules, now);
        messages.save(TrainingMessage.fromAi(
                sessionId, session.getPhase(), sequence + 1, ReviewedTexts.SCENARIO_OPENING, now));
        sessions.saveAndFlush(session);

        MessageDto opening = MessageDto.reviewed(MessageSender.AI, ReviewedTexts.SCENARIO_OPENING);
        return TurnResponse.of(session, opening, false, false);
    }

    private TurnResponse applyTurn(
            long sessionId, long userId, SubmitTurnRequest request, TrainingTurnSuggestion suggestion) {
        // AI를 기다리는 사이에 다른 요청이 먼저 반영됐을 수 있어서 다시 읽고 버전을 확인해요.
        TrainingSession session = load(sessionId, userId);
        requireCurrent(session, request.stateVersion());
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
