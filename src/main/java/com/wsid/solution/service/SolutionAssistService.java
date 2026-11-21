package com.wsid.solution.service;

import com.wsid.ai.*;
import com.wsid.ai.dto.*;
import com.wsid.common.error.*;
import com.wsid.solution.content.ApprovedReferenceCatalog;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.*;
import com.wsid.solution.repository.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Read snapshot -> AI outside transaction -> CAS and safe audit atomically. No raw text persistence. */
@Service
public class SolutionAssistService {
    private final SolutionAssistGateway ai;
    private final SensitiveDescriptionGuard guard;
    private final ApprovedReferenceCatalog references;
    private final ResponseCardCatalog cards;
    private final SolutionSessionRepository sessions;
    private final SolutionMessageRepository messages;
    private final PlatformTransactionManager manager;
    private final Duration budget;
    public SolutionAssistService(SolutionAssistGateway ai, SensitiveDescriptionGuard guard, ApprovedReferenceCatalog references,
            ResponseCardCatalog cards, SolutionSessionRepository sessions, SolutionMessageRepository messages,
            PlatformTransactionManager manager, @Value("${wsid.ai.assist-total-timeout:40s}") Duration budget) {
        if (budget == null || budget.isNegative() || budget.isZero()) throw new IllegalArgumentException("assist budget must be positive");
        this.ai=ai; this.guard=guard; this.references=references; this.cards=cards;
        this.sessions=sessions; this.messages=messages; this.manager=manager; this.budget=budget;
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SolutionMessageResponse send(long id, SendSolutionMessageRequest request, Supplier<Optional<SolutionOwner>> owner) {
        long deadline = System.nanoTime() + budget.toNanos();
        SolutionOwner resolved = owner.get().orElseThrow(() -> error(ErrorCode.SOLUTION_NOT_FOUND));
        Snapshot before = transaction(true, deadline).execute(tx -> snapshot(id, resolved, request));
        if (request.purpose() == SendSolutionMessageRequest.Purpose.QUESTION) guard.check(request.content());
        SolutionFacts facts = before.facts();
        PendingAssist previous = before.pending();
        boolean choice = request.purpose() == SendSolutionMessageRequest.Purpose.CLARIFICATION_ANSWER;
        if (choice) facts = facts.withChoice(previous.fact(), request.selected());
        // Prepare cards before AI, but do not persist partial selections on any failure.
        List<ResponseCard> selectedCards;
        try { selectedCards = ResponseRules.cards(facts, cards); }
        catch (ResponseRules.MissingResponseCardException e) { throw error(ErrorCode.RESPONSE_CONTENT_UNAVAILABLE); }
        SolutionFacts updated = facts;
        Result result;
        try {
            result = within(deadline, () -> evaluate(request, updated, previous, before.fraudType(), deadline));
        } catch (AiUnavailableException e) { throw error(ErrorCode.AI_SERVICE_UNAVAILABLE); }
        if (!owner.get().filter(resolved::equals).isPresent()) throw error(ErrorCode.SOLUTION_NOT_FOUND);
        try {
            return transaction(false, deadline).execute(tx -> {
                snapshot(id, resolved, request); // identity, status, duplicate, version, question are checked again
                checkDeadline(deadline);
                Set<SolutionFact> skipped = new HashSet<>();
                for (String key : messages.answeredUnknownKeys(id)) skipped.add(SolutionFact.fromKey(key).orElseThrow());
                if (choice && request.selected() == FactValue.UNKNOWN) skipped.add(previous.fact());
                SolutionQuestion next = SolutionQuestion.next(updated, skipped).orElse(null);
                Instant now = Instant.now();
                if (sessions.compareAndSetAssist(id, resolved, request.stateVersion(),
                        SolutionSession.prepareUpdate(updated, selectedCards, next, now),
                        SolutionSession.encodePendingAssist(result.pending())) != 1) throw new Race();
                int sequence = Math.addExact(messages.lastSequence(id), 1);
                messages.save(SolutionMessage.assist(id, sequence, request.purpose().name(), request.questionId(),
                        choice ? previous.fact() : null, choice ? request.selected() : null,
                        request.requestId(), result.assistant().referenceCodes(), now));
                messages.flush();
                checkDeadline(deadline); // rollback if persistence consumed the remaining processing budget
                return new SolutionMessageResponse(updated.toKeyMap(), selectedCards.stream().map(StartSolutionResponse.Card::from).toList(),
                        result.pending() == null ? StartSolutionResponse.Question.from(next) : null,
                        Math.addExact(request.stateVersion(), 1), result.assistant());
            });
        } catch (Race | OptimisticLockingFailureException | PessimisticLockingFailureException e) {
            throw classify(id, resolved, request);
        } catch (DataIntegrityViolationException e) {
            if (!messageConstraint(e)) throw e;
            throw classify(id, resolved, request);
        }
    }
    private Result evaluate(SendSolutionMessageRequest input, SolutionFacts facts, PendingAssist previous, String fraudType, long deadline) {
        boolean first = input.purpose() == SendSolutionMessageRequest.Purpose.QUESTION;
        var plan = ai.plan(new SolutionAssistPlanRequest(first ? input.content() : null,
                first ? null : previous.questionIntent(), facts.toKeyMap(), first ? List.of() : previous.askedFactKeys(),
                first ? 3 : previous.remainingClarifications(), UUID.randomUUID(), fraudType));
        if (plan.status() == SolutionAssistStatus.NEED_USER_INPUT) {
            var keys = new ArrayList<>(first ? List.<String>of() : previous.askedFactKeys());
            String key = plan.clarification().factKey(); keys.add(key);
            var pending = new PendingAssist(UUID.randomUUID(), SolutionFact.fromKey(key).orElseThrow(), plan.questionIntent(),
                    keys, (first ? 3 : previous.remainingClarifications()) - 1);
            return new Result(pending, SolutionAssistant.pending(pending));
        }
        if (plan.status() == SolutionAssistStatus.UNABLE_TO_ANSWER) return unable(plan.reasonCode());
        var approved = references.approved(plan.candidateReferences());
        if (approved.isEmpty()) return unable(SolutionAssistReason.NO_EVIDENCE);
        checkDeadline(deadline);
        var answer = ai.answer(new SolutionAssistAnswerRequest(first ? input.content() : null, plan.questionIntent(),
                facts.toKeyMap(), approved, UUID.randomUUID(), fraudType));
        if (answer.status() == SolutionAssistStatus.UNABLE_TO_ANSWER) return unable(answer.reasonCode());
        return new Result(null, new SolutionAssistant(answer.status(), answer.answer(), answer.referenceCodes(), null, null));
    }
    private static Result unable(SolutionAssistReason reason) { return new Result(null, SolutionAssistant.unable(reason)); }
    private Snapshot snapshot(long id, SolutionOwner owner, SendSolutionMessageRequest request) {
        var s = sessions.findOwned(id, owner).orElseThrow(() -> error(ErrorCode.SOLUTION_NOT_FOUND));
        if (messages.existsBySolutionIdAndClientRequestId(id, request.requestId())) throw error(ErrorCode.DUPLICATE_REQUEST);
        if (s.getStatus() != SolutionStatus.ACTIVE) throw error(ErrorCode.SOLUTION_FINISHED);
        if (s.getStateVersion() != request.stateVersion()) throw error(ErrorCode.STATE_VERSION_CONFLICT);
        var pending = s.readPendingAssist();
        if (request.purpose() == SendSolutionMessageRequest.Purpose.QUESTION) {
            if (pending != null) throw error(ErrorCode.ASSIST_QUESTION_PENDING);
        } else if (pending == null || !pending.questionId().equals(request.questionId())
                || s.readFacts().get(pending.fact()) != FactValue.UNKNOWN) throw error(ErrorCode.INVALID_REQUEST);
        return new Snapshot(s.readFacts(), pending, s.getFraudTypeCode());
    }
    private TransactionTemplate transaction(boolean readOnly, long deadline) {
        checkDeadline(deadline);
        var tx = new TransactionTemplate(manager); tx.setReadOnly(readOnly);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toSeconds(deadline-System.nanoTime()) + 1)));
        return tx;
    }
    private Result within(long deadline, Callable<Result> call) {
        checkDeadline(deadline);
        // A virtual thread waits only for the remaining budget. Cancellation does not perform any DB writes.
        var task = new FutureTask<>(call); Thread.startVirtualThread(task);
        try { return task.get(Math.max(1, deadline-System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw error(ErrorCode.AI_SERVICE_UNAVAILABLE); }
        catch (TimeoutException e) { throw error(ErrorCode.AI_SERVICE_UNAVAILABLE); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof AiUnavailableException failure) throw failure;
            throw new AiUnavailableException("assist 처리에 실패했어요.");
        } finally { task.cancel(true); }
    }
    private ApiException classify(long id, SolutionOwner owner, SendSolutionMessageRequest input) {
        var read = new TransactionTemplate(manager); read.setReadOnly(true);
        return read.execute(tx -> {
            var s = sessions.findOwned(id, owner);
            if (s.isEmpty()) return error(ErrorCode.SOLUTION_NOT_FOUND);
            if (messages.existsBySolutionIdAndClientRequestId(id, input.requestId())) return error(ErrorCode.DUPLICATE_REQUEST);
            if (s.get().getStatus() != SolutionStatus.ACTIVE) return error(ErrorCode.SOLUTION_FINISHED);
            return error(ErrorCode.STATE_VERSION_CONFLICT);
        });
    }
    private static boolean messageConstraint(Throwable e) {
        for (; e != null; e=e.getCause()) if (e instanceof ConstraintViolationException c)
            return "uq_solution_messages_request".equals(c.getConstraintName()) || "uq_solution_messages_sequence".equals(c.getConstraintName());
        return false;
    }
    private static void checkDeadline(long deadline) { if (System.nanoTime() >= deadline) throw error(ErrorCode.AI_SERVICE_UNAVAILABLE); }
    private static ApiException error(ErrorCode code) { return new ApiException(code); }
    private record Snapshot(SolutionFacts facts, PendingAssist pending, String fraudType) {}
    private record Result(PendingAssist pending, SolutionAssistant assistant) {}
    private static class Race extends RuntimeException {}
}
