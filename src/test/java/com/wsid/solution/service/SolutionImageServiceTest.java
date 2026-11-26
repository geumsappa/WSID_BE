package com.wsid.solution.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wsid.ai.AiClient;
import com.wsid.ai.AiUnavailableException;
import com.wsid.ai.dto.OcrExtractResult;
import com.wsid.ai.dto.OcrImage;
import com.wsid.ai.dto.OcrImageType;
import com.wsid.ai.dto.SolutionAssistStatus;
import com.wsid.solution.domain.SolutionSession;
import com.wsid.solution.domain.SolutionStatus;
import com.wsid.solution.dto.SolutionAssistant;
import com.wsid.solution.dto.SolutionMessageResponse;
import com.wsid.solution.repository.SolutionSessionRepository;
import java.util.Map;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.SolutionOwner;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class SolutionImageServiceTest {
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0};
    private static final byte[] JPEG = {(byte) 255, (byte) 216, (byte) 255, 0};

    private final AiClient ai = mock(AiClient.class);
    private final SendSolutionMessageService messages = mock(SendSolutionMessageService.class);
    private final SolutionImageAdviceService advice = mock(SolutionImageAdviceService.class);
    private final SolutionSessionRepository sessions = mock(SolutionSessionRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SolutionImageService service;
    private final SolutionOwner owner = SolutionOwner.member(7L);

    {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new SolutionImageService(ai, messages, advice, sessions, manager);
    }
    private final UUID requestId = UUID.randomUUID();

    private static MultipartFile file(String name, String type, byte[] bytes) {
        return new MockMultipartFile("images", name, type, bytes);
    }

    private static void fails(ErrorCode code, Runnable run) {
        assertEquals(code, assertThrows(ApiException.class, run::run).errorCode());
    }

    @Test void photoAssistContextUsesSummaryAndMaskedTextButNeverRawText() {
        var result = new OcrExtractResult("원문 010-1234-5678", true, OcrImageType.MESSENGER_CHAT,
                "상대가 계좌를 보냈어요.", "가".repeat(700));
        String text = SolutionImageService.assistContext(result);
        assertTrue(text.startsWith("[사진 · " + OcrImageType.MESSENGER_CHAT.label() + "] 요약: 상대가 계좌를 보냈어요."));
        assertTrue(text.contains("\n사진 속 글자: " + "가".repeat(SolutionImageService.MAX_PHOTO_TEXT)));
        assertFalse(text.contains("가".repeat(SolutionImageService.MAX_PHOTO_TEXT + 1)));
        assertFalse(text.contains("010-1234"));
        // 이전 FastAPI처럼 maskedText가 없으면 요약만, 요약도 없으면 사진 문맥을 남기지 않아요
        assertEquals("[사진 · " + OcrImageType.SMS.label() + "] 요약: 문자 요약",
                SolutionImageService.assistContext(new OcrExtractResult("원문", true, OcrImageType.SMS, "문자 요약")));
        assertNull(SolutionImageService.assistContext(new OcrExtractResult("원문", true)));
    }

    @Test void acceptsPngAndJpegBySignatureAndHidesOriginalFileName() {
        List<OcrImage> images = SolutionImageService.validate(List.of(
                file("내 카톡 캡처.png", "image/png", PNG), file("문자.jpeg", "application/octet-stream", JPEG)));
        assertEquals("image.png", images.get(0).filename());
        assertEquals("image/png", images.get(0).contentType());
        assertEquals("image.jpg", images.get(1).filename());
        assertEquals("image/jpeg", images.get(1).contentType());
    }

    @Test void rejectsMissingTooManyTooLargeAndNonImageFiles() {
        fails(ErrorCode.INVALID_REQUEST, () -> SolutionImageService.validate(null));
        fails(ErrorCode.INVALID_REQUEST, () -> SolutionImageService.validate(List.of()));
        fails(ErrorCode.FILE_TOO_LARGE, () -> SolutionImageService.validate(List.of(
                file("a", "image/png", PNG), file("b", "image/png", PNG), file("c", "image/png", PNG), file("d", "image/png", PNG))));
        byte[] large = new byte[(int) SolutionImageService.MAX_IMAGE_BYTES + 1];
        System.arraycopy(PNG, 0, large, 0, PNG.length);
        fails(ErrorCode.FILE_TOO_LARGE, () -> SolutionImageService.validate(List.of(file("a", "image/png", large))));
        // 확장자·Content-Type이 이미지여도 내용이 PNG·JPEG가 아니면 거부해요.
        fails(ErrorCode.INVALID_REQUEST, () -> SolutionImageService.validate(List.of(
                file("a.png", "image/png", "<html>".getBytes()))));
        fails(ErrorCode.INVALID_REQUEST, () -> SolutionImageService.validate(List.of(file("a.png", "image/png", new byte[0]))));
    }

    @Test void invalidFilesNeverReachOcrOrSession() {
        fails(ErrorCode.INVALID_REQUEST, () -> service.send(301, requestId, 2,
                List.of(file("a.gif", "image/gif", "GIF89a".getBytes())), Optional::empty));
        verifyNoInteractions(ai, messages);
    }

    @SuppressWarnings("unchecked")
    @Test void sendPassesOcrAsDeferredStepToMessageFlow() {
        when(ai.extractText(any())).thenReturn(new OcrExtractResult("  거래 안내 문구  ", true));
        when(messages.sendRecognizedText(eq(301L), eq(requestId), eq(2), any(), any())).thenAnswer(i -> {
            verifyNoInteractions(ai); // OCR은 상담 확인 뒤 메시지 흐름 안에서 실행돼요
            Supplier<SendSolutionMessageService.RecognizedPhoto> recognize = i.getArgument(3);
            assertEquals("사진 종류: 기타 자료\n인식 문자: 거래 안내 문구", recognize.get().content());
            return saved(3);
        });
        Supplier<Optional<SolutionOwner>> owner = Optional::empty;
        service.send(301, requestId, 2, List.of(file("a.png", "image/png", PNG)), owner);
        verify(ai).extractText(any());
    }

    @Test void mapsOcrResultsToSolutionErrors() {
        List<OcrImage> images = SolutionImageService.validate(List.of(file("a.png", "image/png", PNG)));
        when(ai.extractText(any())).thenReturn(OcrExtractResult.failed());
        fails(ErrorCode.OCR_FAILED, () -> service.recognize(images, requestId));
        when(ai.extractText(any())).thenReturn(new OcrExtractResult("   ", true));
        fails(ErrorCode.OCR_FAILED, () -> service.recognize(images, requestId));
        when(ai.extractText(any())).thenReturn(new OcrExtractResult("", false));
        fails(ErrorCode.OCR_FAILED, () -> service.recognize(images, requestId));
        when(ai.extractText(any())).thenThrow(new AiUnavailableException("down"));
        fails(ErrorCode.AI_SERVICE_UNAVAILABLE, () -> service.recognize(images, requestId));
    }

    @Test void sensitiveTextIsNotRejectedAndRecordKeepsOnlyTypeLabel() {
        List<OcrImage> images = SolutionImageService.validate(List.of(file("a.png", "image/png", PNG)));
        when(ai.extractText(any())).thenReturn(new OcrExtractResult("010-1234-5678 계좌 110-123-456789", false,
                OcrImageType.TRANSFER_RECEIPT, "50만원을 송금한 내역입니다."));
        var photo = service.recognize(images, requestId);
        assertEquals("사진 종류: 송금 내역\n요약: 50만원을 송금한 내역입니다.\n인식 문자: 010-1234-5678 계좌 110-123-456789",
                photo.content());
        assertEquals("사진(송금 내역)을 제출했습니다.", photo.userMessage());
        assertFalse(photo.toString().contains("010"));
    }

    @Test void extractInputFitsPublicLimitWithoutSplittingSurrogates() {
        String longText = "가".repeat(2000);
        String input = SolutionImageService.extractInput(new OcrExtractResult(longText, true, OcrImageType.SMS, "요약"));
        assertEquals(1000, input.length());
        assertTrue(input.startsWith("사진 종류: 문자 메시지\n요약: 요약\n인식 문자: 가"));
        String emoji = "😀".repeat(600);
        String cut = SolutionImageService.extractInput(new OcrExtractResult(emoji, true, OcrImageType.OTHER, null));
        assertTrue(cut.length() <= 1000);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
        assertEquals("사진(기타 자료)을 제출했습니다.", SolutionImageService.userMessage(OcrImageType.OTHER));
    }

    @SuppressWarnings("unchecked")
    private void savedAfterRunningOcr(int version) {
        when(messages.sendRecognizedText(anyLong(), any(), anyInt(), any(), any())).thenAnswer(i -> {
            ((java.util.function.Supplier<?>) i.getArgument(3)).get();
            return saved(version);
        });
    }

    @Test void attachesAdviceOnlyWhenSessionIsStillCurrent() {
        savedAfterRunningOcr(3);
        when(ai.extractText(any())).thenReturn(new OcrExtractResult("[Web발신] 택배 주소 확인", true, OcrImageType.SMS,
                "택배 주소 불일치를 이유로 링크 접속을 요구하는 문자"));
        var assistant = new SolutionAssistant(SolutionAssistStatus.ANSWERED, "안내", List.of("REF_1"), null, null);
        // 안내 요청에는 사진 종류와 요약이 함께 가요(원문은 가지 않음).
        when(advice.optionalAdvice(any(), eq(OcrImageType.SMS), eq("택배 주소 불일치를 이유로 링크 접속을 요구하는 문자"),
                eq(requestId))).thenReturn(assistant);
        SolutionSession session = mock(SolutionSession.class);
        when(session.getStatus()).thenReturn(SolutionStatus.ACTIVE);
        when(session.getStateVersion()).thenReturn(3);
        when(sessions.findOwned(301L, owner)).thenReturn(Optional.of(session));
        var response = service.send(301, requestId, 2, List.of(file("a.png", "image/png", PNG)), () -> Optional.of(owner));
        assertEquals(assistant, response.assistant());
        assertEquals("YES", response.facts().get("transferredMoney"));
        // 안내를 기다리는 사이 다른 요청이 상태를 바꿨으면 오래된 안내는 붙이지 않아요.
        when(session.getStateVersion()).thenReturn(4);
        assertNull(service.send(301, requestId, 2, List.of(file("a.png", "image/png", PNG)), () -> Optional.of(owner)).assistant());
    }

    @Test void adviceFailureStillReturnsSavedFactsAndCards() {
        when(messages.sendRecognizedText(anyLong(), any(), anyInt(), any(), any())).thenReturn(saved(3));
        when(advice.optionalAdvice(any(), any(), any(), any())).thenReturn(null);
        var response = service.send(301, requestId, 2, List.of(file("a.png", "image/png", PNG)), () -> Optional.of(owner));
        assertEquals(3, response.stateVersion());
        assertEquals("YES", response.facts().get("transferredMoney"));
        assertNull(response.assistant());
        verifyNoInteractions(sessions);
    }

    private static SolutionMessageResponse saved(int version) {
        return new SolutionMessageResponse(Map.of("transferredMoney", "YES", "sharedInformation", "UNKNOWN",
                "installedApp", "UNKNOWN", "contactContinues", "UNKNOWN"), List.of(), null, version);
    }
}
