package com.wsid.solution.service;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.CardReference;
import com.wsid.solution.domain.ResponseCard;
import com.wsid.solution.domain.ResponseCardCatalog;
import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.domain.SolutionSession;
import com.wsid.solution.dto.SolutionStateResponse;
import com.wsid.solution.dto.StartSolutionResponse;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SOL-02 상담 복원. 상태·버전을 바꾸지 않고 AI를 호출하지 않아요.
 * 상담과 메시지를 REPEATABLE_READ 읽기 전용 트랜잭션에서 읽어, 중간에 다른 요청이 저장해도 같은 시점 값을 돌려줘요.
 */
@Service
public class SolutionQueryService {
    private static final Logger log = LoggerFactory.getLogger(SolutionQueryService.class);

    private final SolutionSessionRepository sessions;
    private final SolutionMessageRepository messages;
    private final ResponseCardCatalog cards;
    private final TransactionTemplate readTransaction;

    public SolutionQueryService(SolutionSessionRepository sessions, SolutionMessageRepository messages,
            ResponseCardCatalog cards, PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.messages = messages;
        this.cards = cards;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * @param owner 조회 전용 소유자. 비어 있으면 DB를 조회하지 않고 404예요.
     *              없는 상담·남의 상담·소유자 정보 없음은 모두 같은 SOLUTION_NOT_FOUND예요.
     */
    public SolutionStateResponse get(long solutionId, Supplier<Optional<SolutionOwner>> owner) {
        SolutionOwner resolved = owner.get().orElseThrow(() -> new ApiException(ErrorCode.SOLUTION_NOT_FOUND));
        return readTransaction.execute(status -> {
            SolutionSession session = sessions.findOwned(solutionId, resolved)
                    .orElseThrow(() -> new ApiException(ErrorCode.SOLUTION_NOT_FOUND));
            var history = messages.findBySolutionIdOrderBySequenceNoAsc(session.getId()).stream()
                    .map(SolutionStateResponse.Message::from).toList();
            return new SolutionStateResponse(session.getId(), session.getStatus(), session.getStateVersion(),
                    session.readFacts().toKeyMap(), currentCards(session),
                    StartSolutionResponse.Question.from(session.readNextQuestion()), history);
        });
    }

    /** 결정 2(방안 A): 저장된 코드로 현재 검토 카드를 보여줘요. 카드가 없어졌으면 빼지 않고 503이에요. */
    private java.util.List<StartSolutionResponse.Card> currentCards(SolutionSession session) {
        var current = new ArrayList<ResponseCard>();
        for (CardReference ref : session.readCardReferences()) {
            Optional<ResponseCard> card = cards.findReviewed(ref.code());
            if (card.isEmpty()) {
                log.warn("SOL-02 검토 카드 없음 solutionId={} card={}", session.getId(), ref.code());
                throw new ApiException(ErrorCode.RESPONSE_CONTENT_UNAVAILABLE);
            }
            current.add(card.get());
        }
        current.sort(Comparator.comparing(ResponseCard::priority)); // 현재 우선순위 기준, 같으면 저장 순서
        return current.stream().map(StartSolutionResponse.Card::from).toList();
    }
}
