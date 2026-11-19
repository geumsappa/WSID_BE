package com.wsid.solution.service;

import com.wsid.ai.SolutionAssistGateway;
import com.wsid.ai.dto.SolutionAssistAnswerRequest;
import com.wsid.ai.dto.SolutionAssistPlanRequest;
import com.wsid.ai.dto.SolutionAssistStatus;
import com.wsid.solution.content.ApprovedReferenceCatalog;
import com.wsid.solution.domain.SolutionFact;
import com.wsid.solution.dto.SolutionAssistant;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * SOL-06 사실 저장 뒤 붙이는 대응 안내(선택). assist PLAN→승인 근거 확인→ANSWER를 한 번 돌려요.
 * <ul>
 *   <li>사진 원문·요약은 보내지 않아요. 고정 질문 문장과 저장된 사실만 보내요.</li>
 *   <li>추가 질문은 하지 않아요(remainingClarifications=0). 근거 없음·범위 밖·장애·시간 초과는 모두 null로 끝나요.</li>
 *   <li>DB에 쓰지 않아요. 실패해도 이미 저장된 사실과 카드는 그대로 응답돼요.</li>
 * </ul>
 */
@Service
public class SolutionImageAdviceService {
    static final String QUESTION = "확인된 피해 사실을 기준으로 지금 해야 할 대응 방법을 안내해 주세요.";
    private static final Logger log = LoggerFactory.getLogger(SolutionImageAdviceService.class);

    private final SolutionAssistGateway ai;
    private final ApprovedReferenceCatalog references;
    private final Duration timeout;

    public SolutionImageAdviceService(SolutionAssistGateway ai, ApprovedReferenceCatalog references,
            @Value("${wsid.ai.image-advice-timeout:20s}") Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("사진 안내 대기 시간은 0보다 커야 해요.");
        }
        this.ai = ai;
        this.references = references;
        this.timeout = timeout;
    }

    /** 안내를 만들 수 없으면 null이에요. 예외를 던지지 않아요. */
    public SolutionAssistant optionalAdvice(Map<String, String> facts, UUID requestId) {
        var task = new FutureTask<>(() -> advise(facts));
        Thread.startVirtualThread(task);
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (TimeoutException e) {
            log.info("SOL-06 안내 시간 초과 requestId={}", requestId);
            return null;
        } catch (ExecutionException e) {
            // 원인 메시지에 AI 응답이 섞일 수 있어 종류만 남겨요.
            log.info("SOL-06 안내 생략 requestId={} cause={}", requestId, e.getCause().getClass().getSimpleName());
            return null;
        } finally {
            task.cancel(true);
        }
    }

    private SolutionAssistant advise(Map<String, String> facts) {
        var keys = Arrays.stream(SolutionFact.values()).map(SolutionFact::key).toList();
        var plan = ai.plan(new SolutionAssistPlanRequest(QUESTION, null, facts, keys, 0, UUID.randomUUID()));
        if (plan.status() != SolutionAssistStatus.EVIDENCE_READY) return null;
        var approved = references.approved(plan.candidateReferences());
        if (approved.isEmpty() || Thread.currentThread().isInterrupted()) return null;
        var answer = ai.answer(new SolutionAssistAnswerRequest(QUESTION, plan.questionIntent(), facts, approved,
                UUID.randomUUID()));
        if (answer.status() != SolutionAssistStatus.ANSWERED) return null;
        return new SolutionAssistant(answer.status(), answer.answer(), answer.referenceCodes(), null, null);
    }

}
