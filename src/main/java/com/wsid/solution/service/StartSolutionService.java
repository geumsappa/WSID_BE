package com.wsid.solution.service;

import com.wsid.ai.AiClient;
import com.wsid.ai.AiUnavailableException;
import com.wsid.ai.dto.SolutionExtractRequest;
import com.wsid.common.domain.InputKind;
import com.wsid.common.domain.MessageSender;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.ResponseCard;
import com.wsid.solution.domain.ResponseCardCatalog;
import com.wsid.solution.domain.ResponseRules;
import com.wsid.solution.domain.SolutionFacts;
import com.wsid.solution.domain.SolutionMessage;
import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.domain.SolutionQuestion;
import com.wsid.solution.domain.SolutionSession;
import com.wsid.solution.domain.SolutionTitles;
import com.wsid.solution.dto.StartSolutionRequest;
import com.wsid.solution.dto.StartSolutionResponse;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SOL-01 상담 시작.
 * 입력·민감정보 확인 → 소유자 결정 → AI-06(트랜잭션 밖) → 사실·카드·질문 결정 → 짧은 저장 트랜잭션.
 * 피해 설명 원문은 AI 요청에만 쓰고 DB·세션·로그에 남기지 않아요. 예외로 목록 제목용 앞 40자만 title에 저장해요.
 */
@Service
public class StartSolutionService {
    /** 접수 기록용 고정 문구. 원래 설명을 복원하는 용도가 아니에요. */
    static final String DESCRIPTION_RECEIVED = "피해 설명을 제출했습니다.";
    static final String CHOICE_START = "설명 없이 직접 선택으로 상담을 시작했습니다.";

    private static final Logger log = LoggerFactory.getLogger(StartSolutionService.class);

    private final AiClient aiClient;
    private final SensitiveDescriptionGuard guard;
    private final ResponseCardCatalog cards;
    private final SolutionSessionRepository sessions;
    private final SolutionMessageRepository messages;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public StartSolutionService(AiClient aiClient, SensitiveDescriptionGuard guard, ResponseCardCatalog cards,
            SolutionSessionRepository sessions, SolutionMessageRepository messages,
            PlatformTransactionManager transactionManager) {
        this(aiClient, guard, cards, sessions, messages, transactionManager, Clock.systemUTC());
    }

    StartSolutionService(AiClient aiClient, SensitiveDescriptionGuard guard, ResponseCardCatalog cards,
            SolutionSessionRepository sessions, SolutionMessageRepository messages,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.aiClient = aiClient;
        this.guard = guard;
        this.cards = cards;
        this.sessions = sessions;
        this.messages = messages;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param owner 민감정보 검사를 통과한 뒤에만 소유자를 결정하도록 지연 호출해요.
     *              거부된 요청으로 익명 소유자 UUID를 발급하지 않아요.
     */
    public StartSolutionResponse start(StartSolutionRequest request, Supplier<SolutionOwner> owner) {
        request.description().ifPresent(guard::check);
        SolutionOwner resolvedOwner = owner.get();
        UUID requestId = UUID.randomUUID();

        boolean described = request.description().isPresent();
        SolutionFacts facts = request.description().map(text -> extract(text, requestId))
                .orElseGet(SolutionFacts::unknown);
        List<ResponseCard> responseCards;
        try {
            responseCards = ResponseRules.cards(facts, cards);
        } catch (ResponseRules.MissingResponseCardException e) {
            log.warn("SOL-01 검토 카드 없음 requestId={} card={}", requestId, e.code());
            throw new ApiException(ErrorCode.RESPONSE_CONTENT_UNAVAILABLE);
        }
        SolutionQuestion question = SolutionQuestion.next(facts).orElse(null);
        String title = request.description().map(SolutionTitles::fromDescription).orElse(null);

        return transaction.execute(status -> {
            Instant now = clock.instant();
            SolutionSession session = sessions.saveAndFlush(
                    SolutionSession.start(resolvedOwner, facts, responseCards, question, title, now));
            long id = session.getId();
            messages.save(described
                    ? SolutionMessage.of(id, MessageSender.USER, InputKind.TEXT, 1, DESCRIPTION_RECEIVED, requestId, now)
                    : SolutionMessage.of(id, MessageSender.SYSTEM, InputKind.TEXT, 1, CHOICE_START, requestId, now));
            if (question != null) {
                messages.save(SolutionMessage.of(id, MessageSender.SYSTEM, InputKind.TEXT, 2, question.content(), null, now));
            }
            messages.flush();
            return new StartSolutionResponse(id, session.getStatus(), session.getStateVersion(), facts.toKeyMap(),
                    responseCards.stream().map(StartSolutionResponse.Card::from).toList(),
                    StartSolutionResponse.Question.from(question));
        });
    }

    /** AI 실패·타임아웃·계약 위반만 직접 선택으로 넘겨요. 요청 구성 오류는 숨기지 않아요. */
    private SolutionFacts extract(String description, UUID requestId) {
        var aiRequest = new SolutionExtractRequest(description, Map.of(), requestId);
        try {
            return SolutionFacts.fromStrings(aiClient.extractSolutionFacts(aiRequest).facts());
        } catch (AiUnavailableException e) {
            log.info("SOL-01 AI-06 사용 불가, 직접 선택으로 시작 requestId={}", requestId);
            return SolutionFacts.unknown();
        }
    }
}
