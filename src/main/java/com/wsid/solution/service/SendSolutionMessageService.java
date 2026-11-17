package com.wsid.solution.service;

import com.wsid.ai.AiClient;
import com.wsid.ai.AiUnavailableException;
import com.wsid.ai.dto.SolutionExtractRequest;
import com.wsid.common.domain.InputKind;
import com.wsid.common.domain.MessageSender;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.SendSolutionMessageRequest;
import com.wsid.solution.dto.SolutionMessageResponse;
import com.wsid.solution.dto.StartSolutionResponse;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
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

/** SOL-03: 조회 → AI(트랜잭션 밖) → 소유권·버전 재검증 → 원자적 저장. */
@Service
public class SendSolutionMessageService {
    private static final Logger log = LoggerFactory.getLogger(SendSolutionMessageService.class);
    private final AiClient ai;
    private final SensitiveDescriptionGuard guard;
    private final ResponseCardCatalog cards;
    private final SolutionSessionRepository sessions;
    private final SolutionMessageRepository messages;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    private final Clock clock;

    @Autowired
    public SendSolutionMessageService(AiClient ai, SensitiveDescriptionGuard guard, ResponseCardCatalog cards,
            SolutionSessionRepository sessions, SolutionMessageRepository messages, PlatformTransactionManager manager) {
        this(ai, guard, cards, sessions, messages, manager, Clock.systemUTC());
    }

    SendSolutionMessageService(AiClient ai, SensitiveDescriptionGuard guard, ResponseCardCatalog cards,
            SolutionSessionRepository sessions, SolutionMessageRepository messages,
            PlatformTransactionManager manager, Clock clock) {
        this.ai = ai;
        this.guard = guard;
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

    /** 요청 스레드의 기존 소유자만 확인해요. 호출자의 트랜잭션도 AI 구간에는 유지하지 않아요. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SolutionMessageResponse send(long id, SendSolutionMessageRequest request,
            Supplier<Optional<SolutionOwner>> owner) {
        SolutionOwner resolved = existingOwner(owner);
        Snapshot snapshot = read.execute(tx -> snapshot(id, resolved, request));
        SolutionFacts extracted = null;
        if (request.isText()) {
            guard.check(request.content());
            var aiRequest = new SolutionExtractRequest(request.content(), snapshot.facts().knownValues(), request.requestId());
            try {
                extracted = SolutionFacts.fromStrings(ai.extractSolutionFacts(aiRequest).facts());
            } catch (AiUnavailableException e) {
                log.info("SOL-03 AI-06 사용 불가 requestId={}", request.requestId());
                throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE);
            }
        }
        if (!resolved.equals(existingOwner(owner))) throw new ApiException(ErrorCode.SOLUTION_NOT_FOUND);
        SolutionFacts result = extracted;
        try {
            return write.execute(tx -> apply(id, resolved, request, result));
        } catch (ConcurrentUpdate | OptimisticLockingFailureException | PessimisticLockingFailureException e) {
            throw classifyRace(id, resolved, request);
        } catch (DataIntegrityViolationException e) {
            if (!isMessageRace(e)) throw e;
            throw classifyRace(id, resolved, request);
        }
    }

    private Snapshot snapshot(long id, SolutionOwner owner, SendSolutionMessageRequest request) {
        SolutionSession session = checkedSession(id, owner, request);
        SolutionQuestion question = session.readNextQuestion();
        if (!request.isText() && (question == null || !question.factKey().equals(request.fact().key())
                || !question.options().contains(request.selected().name()))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return new Snapshot(session.readFacts(), question);
    }

    private SolutionSession checkedSession(long id, SolutionOwner owner, SendSolutionMessageRequest request) {
        SolutionSession session = sessions.findOwned(id, owner)
                .orElseThrow(() -> new ApiException(ErrorCode.SOLUTION_NOT_FOUND));
        if (messages.existsBySolutionIdAndClientRequestId(id, request.requestId())) {
            throw new ApiException(ErrorCode.DUPLICATE_REQUEST);
        }
        if (session.getStatus() != SolutionStatus.ACTIVE) throw new ApiException(ErrorCode.SOLUTION_FINISHED);
        if (session.getStateVersion() != request.stateVersion()) throw new ApiException(ErrorCode.STATE_VERSION_CONFLICT);
        return session;
    }

    private SolutionMessageResponse apply(long id, SolutionOwner owner, SendSolutionMessageRequest request,
            SolutionFacts extracted) {
        Snapshot before = snapshot(id, owner, request);
        SolutionFacts updated;
        if (request.isText()) {
            SolutionFacts.Merge merged = before.facts().mergeExtracted(extracted);
            updated = merged.facts();
            if (merged.conflictCount() > 0) {
                log.info("SOL-03 기존 사실 유지 requestId={} conflictCount={}", request.requestId(), merged.conflictCount());
            }
        } else {
            updated = before.facts().withChoice(request.fact(), request.selected());
        }
        Set<SolutionFact> skipped = new HashSet<>();
        for (String key : messages.answeredUnknownKeys(id)) {
            skipped.add(SolutionFact.fromKey(key).orElseThrow(() -> new IllegalStateException("저장된 선택 키가 올바르지 않아요.")));
        }
        if (!request.isText() && request.selected() == FactValue.UNKNOWN) skipped.add(request.fact());
        SolutionQuestion question = SolutionQuestion.next(updated, skipped).orElse(null);
        java.util.List<ResponseCard> selectedCards;
        try {
            selectedCards = ResponseRules.cards(updated, cards);
        } catch (ResponseRules.MissingResponseCardException e) {
            log.warn("SOL-03 검토 카드 없음 requestId={} card={}", request.requestId(), e.code());
            throw new ApiException(ErrorCode.RESPONSE_CONTENT_UNAVAILABLE);
        }
        Instant now = clock.instant();
        if (sessions.compareAndSet(id, owner, request.stateVersion(),
                SolutionSession.prepareUpdate(updated, selectedCards, question, now)) != 1) throw new ConcurrentUpdate();
        int sequence = Math.addExact(messages.lastSequence(id), 1);
        messages.save(request.isText()
                ? SolutionMessage.of(id, MessageSender.USER, InputKind.TEXT, sequence,
                        StartSolutionService.DESCRIPTION_RECEIVED, request.requestId(), now)
                : SolutionMessage.choice(id, sequence, request.fact(), request.selected(), request.requestId(), now));
        if (question != null && !Objects.equals(question, before.question())) {
            messages.save(SolutionMessage.of(id, MessageSender.SYSTEM, InputKind.TEXT, Math.addExact(sequence, 1),
                    question.content(), null, now));
        }
        messages.flush();
        SolutionSession stored = sessions.findOwned(id, owner)
                .orElseThrow(() -> new IllegalStateException("갱신한 상담을 읽을 수 없어요."));
        return new SolutionMessageResponse(updated.toKeyMap(), selectedCards.stream().map(StartSolutionResponse.Card::from).toList(),
                StartSolutionResponse.Question.from(question), stored.getStateVersion());
    }

    /** 반드시 실패한 저장 트랜잭션이 끝난 뒤 새 트랜잭션에서 확인해요. */
    private ApiException classifyRace(long id, SolutionOwner owner, SendSolutionMessageRequest request) {
        return read.execute(tx -> {
            var session = sessions.findOwned(id, owner);
            if (session.isEmpty()) return new ApiException(ErrorCode.SOLUTION_NOT_FOUND);
            if (messages.existsBySolutionIdAndClientRequestId(id, request.requestId())) return new ApiException(ErrorCode.DUPLICATE_REQUEST);
            if (session.get().getStatus() != SolutionStatus.ACTIVE) return new ApiException(ErrorCode.SOLUTION_FINISHED);
            return new ApiException(ErrorCode.STATE_VERSION_CONFLICT);
        });
    }

    private static boolean isMessageRace(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return "uq_solution_messages_request".equals(violation.getConstraintName())
                        || "uq_solution_messages_sequence".equals(violation.getConstraintName());
            }
        }
        return false;
    }

    private static SolutionOwner existingOwner(Supplier<Optional<SolutionOwner>> owner) {
        return owner.get().orElseThrow(() -> new ApiException(ErrorCode.SOLUTION_NOT_FOUND));
    }

    private record Snapshot(SolutionFacts facts, SolutionQuestion question) {}
    private static final class ConcurrentUpdate extends RuntimeException {}
}
