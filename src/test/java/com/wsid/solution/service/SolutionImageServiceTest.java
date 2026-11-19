package com.wsid.solution.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wsid.ai.AiClient;
import com.wsid.ai.AiUnavailableException;
import com.wsid.ai.dto.OcrExtractResult;
import com.wsid.ai.dto.OcrImage;
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
    private final SolutionImageService service = new SolutionImageService(ai, messages);
    private final UUID requestId = UUID.randomUUID();

    private static MultipartFile file(String name, String type, byte[] bytes) {
        return new MockMultipartFile("images", name, type, bytes);
    }

    private static void fails(ErrorCode code, Runnable run) {
        assertEquals(code, assertThrows(ApiException.class, run::run).errorCode());
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
            Supplier<String> recognize = i.getArgument(3);
            assertEquals("거래 안내 문구", recognize.get());
            return null;
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
        fails(ErrorCode.SENSITIVE_DATA_DETECTED, () -> service.recognize(images, requestId));
        when(ai.extractText(any())).thenReturn(new OcrExtractResult("가".repeat(1001), true));
        fails(ErrorCode.OCR_FAILED, () -> service.recognize(images, requestId));
        when(ai.extractText(any())).thenThrow(new AiUnavailableException("down"));
        fails(ErrorCode.AI_SERVICE_UNAVAILABLE, () -> service.recognize(images, requestId));
    }
}
