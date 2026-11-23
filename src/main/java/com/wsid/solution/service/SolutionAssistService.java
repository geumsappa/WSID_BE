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
    /** v1.2 조건 질문 사용 여부. 끄면 confirmedConditions를 보내지 않아 v1과 같아요(즉시 롤백용). */
    private final boolean conditionsEnabled;
    /** 사진 내용을 assist history로 보낼지. 끄면 history=null로 v1.2 이전과 같아요(즉시 롤백용). */
    private final boolean historyEnabled;
    /** history 한 턴의 최대 길이(FastAPI 계약). */
    static final int MAX_HISTORY_TURN = 2_000;
    public SolutionAssistService(SolutionAssistGateway ai, SensitiveDescriptionGuard guard, ApprovedReferenceCatalog references,
            ResponseCardCatalog cards, SolutionSessionRepository sessions, SolutionMessageRepository messages,
            PlatformTransactionManager manager, Duration budget) {
        this(ai, guard, references, cards, sessions, messages, manager, budget, false);
    }
    public SolutionAssistService(SolutionAssistGateway ai, SensitiveDescriptionGuard guard, ApprovedReferenceCatalog references,
            ResponseCardCatalog cards, SolutionSessionRepository sessions, SolutionMessageRepository messages,
            PlatformTransactionManager manager, Duration budget, boolean conditionsEnabled) {
        this(ai, guard, references, cards, sessions, messages, manager, budget, conditionsEnabled, true);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public SolutionAssistService(SolutionAssistGateway ai, SensitiveDescriptionGuard guard, ApprovedReferenceCatalog references,
            ResponseCardCatalog cards, SolutionSessionRepository sessions, SolutionMessageRepository messages,
            PlatformTransactionManager manager, @Value("${wsid.ai.assist-total-timeout:40s}") Duration budget,
            @Value("${wsid.ai.assist-conditions-enabled:false}") boolean conditionsEnabled,
            @Value("${wsid.ai.assist-history-enabled:true}") boolean historyEnabled) {
        if (budget == null || budget.isNegative() || budget.isZero()) throw new IllegalArgumentException("assist budget must be positive");
        this.ai=ai; this.guard=guard; this.references=references; this.cards=cards;
        this.sessions=sessions; this.messages=messages; this.manager=manager; this.budget=budget;
        this.conditionsEnabled=conditionsEnabled; this.historyEnabled=historyEnabled;
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SolutionMessageResponse send(long id, SendSolutionMessageRequest request, Supplier<Optional<SolutionOwner>> owner) {
        long deadline = System.nanoTime() + budget.toNanos();
        SolutionOwner resolved = owner.get().orElseThrow(() -> error(ErrorCode.SOLUTION_NOT_FOUND));
        Snapshot before = transaction(true, deadline).execute(tx -> snapshot(id, resolved, request));
        if (request.purpose() == SendSolutionMessageRequest.Purpose.QUESTION) guard.check(request.content());
        // 소유권·상태·버전을 확인한 뒤에만 읽어요. AI 호출은 트랜잭션 밖이고, 저장 전 CAS 재검증은 그대로예요.
        List<SolutionAssistHistoryTurn> history = historyEnabled
                ? transaction(true, deadline).execute(tx -> conversationHistory(id)) : null;
        SolutionFacts facts = before.facts();
        PendingAssist previous = before.pending();
        boolean choice = request.purpose() == SendSolutionMessageRequest.Purpose.CLARIFICATION_ANSWER;
        // 조건 답(v1.2)은 네 사실·카드에 넣지 않고 확정 조건 목록에만 더해요.
        boolean conditionAnswer = choice && previous.asksCondition();
        if (choice && !conditionAnswer) facts = facts.withChoice(previous.fact(), request.selected());
        List<SolutionAssistConfirmedCondition> confirmed = new ArrayList<>(choice ? previous.confirmedConditions() : List.of());
        if (conditionAnswer) confirmed.add(SolutionAssistConfirmedCondition.of(previous.condition(), request.selected().name()));
        // Prepare cards before AI, but do not persist partial selections on any failure.
        List<ResponseCard> selectedCards;
        try { selectedCards = ResponseRules.cards(facts, cards); }
        catch (ResponseRules.MissingResponseCardException e) { throw error(ErrorCode.RESPONSE_CONTENT_UNAVAILABLE); }
        SolutionFacts updated = facts;
        Result result;
        try {
            // 플래그가 꺼져 있어도 이미 진행 중인 조건 질문 흐름은 끝까지 마쳐요.
            var sent = conditionsEnabled || !confirmed.isEmpty() ? List.copyOf(confirmed) : null;
            result = within(deadline, () -> evaluate(request, updated, previous, sent, before.fraudType(), history, deadline));
        } catch (AiUnavailableException e) { throw error(ErrorCode.AI_SERVICE_UNAVAILABLE); }
        if (!owner.get().filter(resolved::equals).isPresent()) throw error(ErrorCode.SOLUTION_NOT_FOUND);
        try {
            return transaction(false, deadline).execute(tx -> {
                snapshot(id, resolved, request); // identity, status, duplicate, version, question are checked again
                checkDeadline(deadline);
                Set<SolutionFact> skipped = new HashSet<>();
                for (String key : messages.answeredUnknownKeys(id)) skipped.add(SolutionFact.fromKey(key).orElseThrow());
                if (choice && !conditionAnswer && request.selected() == FactValue.UNKNOWN) skipped.add(previous.fact());
                SolutionQuestion next = SolutionQuestion.next(updated, skipped).orElse(null);
                Instant now = Instant.now();
                if (sessions.compareAndSetAssist(id, resolved, request.stateVersion(),
                        SolutionSession.prepareUpdate(updated, selectedCards, next, now),
                        SolutionSession.encodePendingAssist(result.pending())) != 1) throw new Race();
                int sequence = Math.addExact(messages.lastSequence(id), 1);
                messages.save(conditionAnswer
                        ? SolutionMessage.conditionAnswer(id, sequence, request.purpose().name(), request.questionId(),
                                previous.condition().referenceCode(), previous.conditionContent(), request.selected(),
                                request.requestId(), result.assistant().referenceCodes(), now)
                        : SolutionMessage.assist(id, sequence, request.purpose().name(), request.questionId(),
                                choice ? null : request.content(),
                                choice ? previous.fact() : null, choice ? request.selected() : null,
                                request.requestId(), result.assistant().referenceCodes(), now));
                // 답변·답변 불가 안내는 AI 행으로 남겨 새로고침(SOL-02) 때 보이고 다음 질문의 history가 돼요.
                // 확인 질문(NEED_USER_INPUT)은 대기 상태로 복원되므로 행을 만들지 않아요.
                String reply = result.assistant().answer();
                if (result.pending() == null && reply != null && !reply.isBlank())
                    messages.save(SolutionMessage.aiReply(id, Math.addExact(sequence, 1), reply,
                            result.assistant().referenceCodes(), now));
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
    /** 저장된 대화(설명·질문·답변·사진)로 만든 history. 규칙은 SolutionConversationHistory 참고. */
    private List<SolutionAssistHistoryTurn> conversationHistory(long id) {
        return SolutionConversationHistory.from(messages.findBySolutionIdOrderBySequenceNoAsc(id));
    }
    private Result evaluate(SendSolutionMessageRequest input, SolutionFacts facts, PendingAssist previous,
            List<SolutionAssistConfirmedCondition> confirmed, String fraudType, List<SolutionAssistHistoryTurn> history,
            long deadline) {
        boolean first = input.purpose() == SendSolutionMessageRequest.Purpose.QUESTION;
        int remaining = first ? 3 : previous.remainingClarifications();
        // v1.2: 확정 조건 목록(첫 질문은 빈 목록)을 보내 근거 문단 기반 조건 질문을 켜요.
        var plan = ai.plan(new SolutionAssistPlanRequest(first ? input.content() : null,
                first ? null : previous.questionIntent(), facts.toKeyMap(), first ? List.of() : previous.askedFactKeys(),
                remaining, UUID.randomUUID(), fraudType, history, confirmed));
        if (plan.status() == SolutionAssistStatus.NEED_USER_INPUT) {
            var keys = new ArrayList<>(first ? List.<String>of() : previous.askedFactKeys());
            var clarification = plan.clarification();
            if (clarification.isCondition()) {
                var condition = clarification.condition();
                // 조건의 근거 문단도 승인 카탈로그에 있고, 인용이 그 원문에 글자 그대로 있어야 물어요.
                if (!references.containsQuote(condition.referenceCode(), condition.quote()))
                    return unable(SolutionAssistReason.NO_EVIDENCE);
                var pending = new PendingAssist(UUID.randomUUID(), null, condition, clarification.content(),
                        plan.questionIntent(), keys, confirmed, remaining - 1);
                return new Result(pending, SolutionAssistant.pending(pending));
            }
            String key = clarification.factKey(); keys.add(key);
            var pending = new PendingAssist(UUID.randomUUID(), SolutionFact.fromKey(key).orElseThrow(), null, null,
                    plan.questionIntent(), keys, confirmed, remaining - 1);
            return new Result(pending, SolutionAssistant.pending(pending));
        }
        if (plan.status() == SolutionAssistStatus.UNABLE_TO_ANSWER) return unable(plan.reasonCode());
        var approved = references.approved(plan.candidateReferences());
        if (approved.isEmpty()) return unable(SolutionAssistReason.NO_EVIDENCE);
        checkDeadline(deadline);
        var answer = ai.answer(new SolutionAssistAnswerRequest(first ? input.content() : null, plan.questionIntent(),
                facts.toKeyMap(), approved, UUID.randomUUID(), fraudType, history, confirmed));
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
                || (!pending.asksCondition() && s.readFacts().get(pending.fact()) != FactValue.UNKNOWN))
            throw error(ErrorCode.INVALID_REQUEST);
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
