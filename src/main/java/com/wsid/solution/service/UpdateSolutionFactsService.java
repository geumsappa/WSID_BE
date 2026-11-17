package com.wsid.solution.service;

import com.wsid.common.domain.InputKind;
import com.wsid.common.domain.MessageSender;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.SolutionMessageResponse;
import com.wsid.solution.dto.StartSolutionResponse;
import com.wsid.solution.dto.UpdateSolutionFactsRequest;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SOL-04 사용자 직접 사실 정정. AI를 부르지 않아 짧은 쓰기 트랜잭션 하나에서 검사·재계산·CAS·기록을 끝내요.
 * 상태 버전 경계(SolutionSessionUpdates.compareAndSet)는 SOL-03과 같아서 두 API가 같은 버전으로 경쟁하면 하나만 반영돼요.
 */
@Service
public class UpdateSolutionFactsService {
    private static final Logger log = LoggerFactory.getLogger(UpdateSolutionFactsService.class);
    private final ResponseCardCatalog cards;
    private final SolutionSessionRepository sessions;
    private final SolutionMessageRepository messages;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    private final Clock clock;

    @Autowired
    public UpdateSolutionFactsService(ResponseCardCatalog cards, SolutionSessionRepository sessions,
            SolutionMessageRepository messages, PlatformTransactionManager manager) {
        this(cards, sessions, messages, manager, Clock.systemUTC());
    }

    UpdateSolutionFactsService(ResponseCardCatalog cards, SolutionSessionRepository sessions,
            SolutionMessageRepository messages, PlatformTransactionManager manager, Clock clock) {
        this.cards = cards;
        this.sessions = sessions;
        this.messages = messages;
        this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SolutionMessageResponse update(long id, UpdateSolutionFactsRequest request,
            Supplier<Optional<SolutionOwner>> owner) {
        SolutionOwner resolved = owner.get().orElseThrow(() -> new ApiException(ErrorCode.SOLUTION_NOT_FOUND));
        try {
            return write.execute(tx -> apply(id, resolved, request));
        } catch (ConcurrentUpdate | OptimisticLockingFailureException | PessimisticLockingFailureException e) {
            throw classifyRace(id, resolved);
        } catch (DataIntegrityViolationException e) {
            if (!isSequenceRace(e)) throw e;
            throw classifyRace(id, resolved);
        }
    }

    private SolutionMessageResponse apply(long id, SolutionOwner owner, UpdateSolutionFactsRequest request) {
        SolutionSession session = sessions.findOwned(id, owner)
                .orElseThrow(() -> new ApiException(ErrorCode.SOLUTION_NOT_FOUND));
        if (session.getStatus() != SolutionStatus.ACTIVE) throw new ApiException(ErrorCode.SOLUTION_FINISHED);
        if (session.getStateVersion() != request.stateVersion()) throw new ApiException(ErrorCode.STATE_VERSION_CONFLICT);
        SolutionQuestion before = session.readNextQuestion();
        // 카드는 수정한 전체 사실로 다시 계산해요. 저장된 card_codes_json은 읽지 않아요(SOL-03과 같음).
        SolutionFacts updated = session.readFacts().withChanges(request.changes());
        Set<SolutionFact> skipped = EnumSet.noneOf(SolutionFact.class);
        for (String key : messages.answeredUnknownKeys(id)) {
            skipped.add(SolutionFact.fromKey(key).orElseThrow(() -> new IllegalStateException("저장된 선택 키가 올바르지 않아요.")));
        }
        for (Map.Entry<SolutionFact, FactValue> change : request.changes().entrySet()) {
            if (change.getValue() == FactValue.UNKNOWN) skipped.add(change.getKey());
        }
        SolutionQuestion question = SolutionQuestion.next(updated, skipped).orElse(null);
        List<ResponseCard> selectedCards;
        try {
            selectedCards = ResponseRules.cards(updated, cards);
        } catch (ResponseRules.MissingResponseCardException e) {
            log.warn("SOL-04 검토 카드 없음 solutionId={} card={}", id, e.code());
            throw new ApiException(ErrorCode.RESPONSE_CONTENT_UNAVAILABLE);
        }
        Instant now = clock.instant();
        if (sessions.compareAndSet(id, owner, request.stateVersion(),
                SolutionSession.prepareUpdate(updated, selectedCards, question, now)) != 1) throw new ConcurrentUpdate();
        int sequence = messages.lastSequence(id);
        for (Map.Entry<SolutionFact, FactValue> change : request.changes().entrySet()) { // SolutionFact 선언 순서
            sequence = Math.addExact(sequence, 1);
            messages.save(SolutionMessage.correction(id, sequence, change.getKey(), change.getValue(), now));
        }
        if (question != null && !Objects.equals(question, before)) {
            messages.save(SolutionMessage.of(id, MessageSender.SYSTEM, InputKind.TEXT, Math.addExact(sequence, 1),
                    question.content(), null, now));
        }
        messages.flush();
        SolutionSession stored = sessions.findOwned(id, owner)
                .orElseThrow(() -> new IllegalStateException("갱신한 상담을 읽을 수 없어요."));
        log.info("SOL-04 사실 수정 solutionId={} changeCount={}", id, request.changes().size());
        return new SolutionMessageResponse(updated.toKeyMap(),
                selectedCards.stream().map(StartSolutionResponse.Card::from).toList(),
                StartSolutionResponse.Question.from(question), stored.getStateVersion());
    }

    /** 실패한 쓰기 트랜잭션이 끝난 뒤 새 읽기 트랜잭션에서 원인을 나눠요. SOL-04에는 요청 UUID가 없어 중복 판정은 없어요. */
    private ApiException classifyRace(long id, SolutionOwner owner) {
        return read.execute(tx -> {
            var session = sessions.findOwned(id, owner);
            if (session.isEmpty()) return new ApiException(ErrorCode.SOLUTION_NOT_FOUND);
            if (session.get().getStatus() != SolutionStatus.ACTIVE) return new ApiException(ErrorCode.SOLUTION_FINISHED);
            return new ApiException(ErrorCode.STATE_VERSION_CONFLICT);
        });
    }

    private static boolean isSequenceRace(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return "uq_solution_messages_sequence".equals(violation.getConstraintName());
            }
        }
        return false;
    }

    private static final class ConcurrentUpdate extends RuntimeException {}
}
