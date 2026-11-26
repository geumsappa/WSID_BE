package com.wsid.solution.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.wsid.ai.AiUnavailableException;
import com.wsid.ai.SolutionAssistGateway;
import com.wsid.ai.dto.*;
import com.wsid.solution.content.ApprovedReferenceCatalog;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SolutionImageAdviceServiceTest {
    private static final Map<String, String> FACTS = Map.of("transferredMoney", "YES", "sharedInformation", "UNKNOWN",
            "installedApp", "NO", "contactContinues", "UNKNOWN");
    private static final List<SolutionCandidateReference> CANDIDATES = List.of(new SolutionCandidateReference("GUIDE", "1"));
    private static final List<VerifiedReference> APPROVED = List.of(new VerifiedReference("GUIDE", "제목", "발췌"));

    private final SolutionAssistGateway ai = mock(SolutionAssistGateway.class);
    private final ApprovedReferenceCatalog references = mock(ApprovedReferenceCatalog.class);
    private final SolutionImageAdviceService service = new SolutionImageAdviceService(ai, references,
            new SensitiveDescriptionGuard(), Duration.ofMillis(300));
    private static final String SUMMARY = "택배 주소 불일치를 이유로 링크 접속을 요구하는 문자";

    private static SolutionAssistPlanResult ready() {
        return new SolutionAssistPlanResult(SolutionAssistStatus.EVIDENCE_READY, SolutionAssistIntent.TRANSFER_RESPONSE,
                null, CANDIDATES, null);
    }

    @Test void answersWithImageTypeAndSummaryWithoutRawTextOrClarification() {
        when(ai.plan(any())).thenReturn(ready());
        when(references.approved(CANDIDATES)).thenReturn(APPROVED);
        when(ai.answer(any())).thenReturn(new SolutionAssistAnswerResult(SolutionAssistStatus.ANSWERED, "지급정지를 요청하세요.",
                List.of("GUIDE"), null));
        var result = service.optionalAdvice(FACTS, OcrImageType.SMS, SUMMARY, UUID.randomUUID());
        assertEquals("지급정지를 요청하세요.", result.answer());
        assertEquals(List.of("GUIDE"), result.referenceCodes());
        var plan = ArgumentCaptor.forClass(SolutionAssistPlanRequest.class);
        verify(ai).plan(plan.capture());
        String expected = "사진 종류: 문자 메시지\n사진 내용: " + SUMMARY + "\n이 사진과 " + SolutionImageAdviceService.QUESTION;
        assertEquals(expected, plan.getValue().question()); // 원문 대신 종류·요약
        assertEquals(0, plan.getValue().remainingClarifications());
        assertEquals(4, plan.getValue().askedFactKeys().size());
        var answer = ArgumentCaptor.forClass(SolutionAssistAnswerRequest.class);
        verify(ai).answer(answer.capture());
        assertEquals(expected, answer.getValue().question());
    }

    @Test void returnsNullWhenNoEvidenceUnableOrClarificationRequested() {
        when(ai.plan(any())).thenReturn(new SolutionAssistPlanResult(SolutionAssistStatus.UNABLE_TO_ANSWER, null, null,
                List.of(), SolutionAssistReason.OUT_OF_SCOPE));
        assertNull(service.optionalAdvice(FACTS, OcrImageType.OTHER, null, UUID.randomUUID()));
        when(ai.plan(any())).thenReturn(ready());
        when(references.approved(any())).thenReturn(List.of()); // 승인 근거 0건
        assertNull(service.optionalAdvice(FACTS, OcrImageType.OTHER, null, UUID.randomUUID()));
        verify(ai, never()).answer(any());
        when(references.approved(any())).thenReturn(APPROVED);
        when(ai.answer(any())).thenReturn(new SolutionAssistAnswerResult(SolutionAssistStatus.UNABLE_TO_ANSWER, null,
                List.of(), SolutionAssistReason.NO_EVIDENCE));
        assertNull(service.optionalAdvice(FACTS, OcrImageType.OTHER, null, UUID.randomUUID()));
    }

    @Test void failuresAndTimeoutNeverThrow() {
        when(ai.plan(any())).thenThrow(new AiUnavailableException("down"));
        assertNull(service.optionalAdvice(FACTS, OcrImageType.OTHER, null, UUID.randomUUID()));
        reset(ai);
        when(ai.plan(any())).thenAnswer(i -> { Thread.sleep(2_000); return ready(); });
        long started = System.nanoTime();
        assertNull(service.optionalAdvice(FACTS, OcrImageType.OTHER, null, UUID.randomUUID()));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1_500);
    }

    @Test void unsafeOrMissingSummaryIsLeftOutOfQuestion() {
        String base = "\n이 사진과 " + SolutionImageAdviceService.QUESTION;
        assertEquals("사진 종류: 송금 내역" + base, service.question(OcrImageType.TRANSFER_RECEIPT, null));
        assertEquals("사진 종류: 송금 내역" + base, service.question(OcrImageType.TRANSFER_RECEIPT, "  "));
        // 계좌·전화번호 같은 숫자나 링크가 섞인 요약은 빼요.
        assertEquals("사진 종류: 문자 메시지" + base, service.question(OcrImageType.SMS, "010-1234-5678로 연락 요구"));
        assertEquals("사진 종류: 문자 메시지" + base, service.question(OcrImageType.SMS, "bit.ly 링크 접속 요구"));
        assertEquals("사진 종류: 기타 자료" + base, service.question(null, null));
        assertEquals("사진 종류: 송금 내역\n사진 내용: 500,000원 이체 완료 화면" + base,
                service.question(OcrImageType.TRANSFER_RECEIPT, "500,000원\n 이체 완료 화면"));
    }
}
