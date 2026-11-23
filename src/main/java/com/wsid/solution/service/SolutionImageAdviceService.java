package com.wsid.solution.service;

import com.wsid.ai.SolutionAssistGateway;
import com.wsid.ai.dto.OcrImageType;
import com.wsid.ai.dto.SolutionAssistHistoryTurn;
import com.wsid.ai.dto.SolutionAssistAnswerRequest;
import com.wsid.ai.dto.SolutionAssistPlanRequest;
import com.wsid.ai.dto.SolutionAssistStatus;
import com.wsid.solution.content.ApprovedReferenceCatalog;
import com.wsid.solution.domain.SolutionFact;
import com.wsid.solution.dto.SolutionAssistant;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * SOL-06 사실 저장 뒤 붙이는 대응 안내(선택). assist PLAN→승인 근거 확인→ANSWER를 한 번 돌려요.
 * <ul>
 *   <li>사진 원문은 보내지 않아요. 사진 종류와 FastAPI가 만든 요약(300자 이내)을 질문에 넣어 사진 내용에 맞는 안내를 받아요.</li>
 *   <li>요약에 계좌·전화번호 같은 숫자나 링크가 보이면 요약은 빼고 종류만 보내요.</li>
 *   <li>추가 질문은 하지 않아요(remainingClarifications=0). 근거 없음·범위 밖·장애·시간 초과는 모두 null로 끝나요.</li>
 *   <li>DB에 쓰지 않아요. 실패해도 이미 저장된 사실과 카드는 그대로 응답돼요.</li>
 * </ul>
 */
@Service
public class SolutionImageAdviceService {
    static final String QUESTION = "확인된 피해 사실을 기준으로 지금 해야 할 대응 방법을 안내해 주세요.";
    private static final Pattern LINK = Pattern.compile("(?i)https?://|www\\.|\\b[\\w-]+\\.(com|net|org|kr|co|io|me|ly)\\b");
    private static final Logger log = LoggerFactory.getLogger(SolutionImageAdviceService.class);

    private final SolutionAssistGateway ai;
    private final ApprovedReferenceCatalog references;
    private final SensitiveDescriptionGuard guard;
    private final Duration timeout;

    public SolutionImageAdviceService(SolutionAssistGateway ai, ApprovedReferenceCatalog references,
            SensitiveDescriptionGuard guard, @Value("${wsid.ai.image-advice-timeout:20s}") Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("사진 안내 대기 시간은 0보다 커야 해요.");
        }
        this.ai = ai;
        this.references = references;
        this.guard = guard;
        this.timeout = timeout;
    }

    /** 안내를 만들 수 없으면 null이에요. 예외를 던지지 않아요. */
    public SolutionAssistant optionalAdvice(Map<String, String> facts, OcrImageType imageType, String summary,
            UUID requestId) {
        return optionalAdvice(facts, question(imageType, summary), null, null, requestId);
    }

    /**
     * 사진·선택지 답 뒤의 후속 안내. question은 서버가 만든 문장이고, history(사진·앞 대화)와 fraudType을 함께 보내
     * 상담 맥락에 맞는 안내를 받아요. 만들 수 없으면 null이에요. 예외를 던지지 않아요.
     */
    public SolutionAssistant optionalAdvice(Map<String, String> facts, String question, String fraudType,
            List<SolutionAssistHistoryTurn> history, UUID requestId) {
        var task = new FutureTask<>(() -> advise(facts, question, fraudType, history));
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

    /**
     * 예: "사진 종류: 문자 메시지\n사진 내용: 택배 주소 불일치를 이유로 링크 접속을 요구하는 문자\n이 사진과 …"
     * 요약이 없거나 안전하지 않으면 종류와 고정 문장만 보내요.
     */
    String question(OcrImageType imageType, String summary) {
        StringBuilder q = new StringBuilder("사진 종류: ").append((imageType == null ? OcrImageType.OTHER : imageType).label())
                .append('\n');
        String safe = safeSummary(summary);
        if (safe != null) q.append("사진 내용: ").append(safe).append('\n');
        return q.append("이 사진과 ").append(QUESTION).toString();
    }

    private String safeSummary(String summary) {
        if (summary == null || summary.isBlank()) return null;
        String text = summary.strip().replaceAll("\\s+", " ");
        if (LINK.matcher(text).find()) return null;
        try {
            guard.check(text);
        } catch (com.wsid.common.error.ApiException e) {
            return null;
        }
        return text;
    }

    private SolutionAssistant advise(Map<String, String> facts, String question, String fraudType,
            List<SolutionAssistHistoryTurn> history) {
        var keys = Arrays.stream(SolutionFact.values()).map(SolutionFact::key).toList();
        var plan = ai.plan(new SolutionAssistPlanRequest(question, null, facts, keys, 0, UUID.randomUUID(),
                fraudType, history));
        if (plan.status() != SolutionAssistStatus.EVIDENCE_READY) return null;
        var approved = references.approved(plan.candidateReferences());
        if (approved.isEmpty() || Thread.currentThread().isInterrupted()) return null;
        var answer = ai.answer(new SolutionAssistAnswerRequest(question, plan.questionIntent(), facts, approved,
                UUID.randomUUID(), fraudType, history));
        if (answer.status() != SolutionAssistStatus.ANSWERED) return null;
        return new SolutionAssistant(answer.status(), answer.answer(), answer.referenceCodes(), null, null);
    }

}
