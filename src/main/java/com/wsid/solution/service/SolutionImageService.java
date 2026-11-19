package com.wsid.solution.service;

import com.wsid.ai.AiClient;
import com.wsid.ai.AiUnavailableException;
import com.wsid.ai.dto.OcrExtractResult;
import com.wsid.ai.dto.OcrImage;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.dto.SolutionMessageResponse;
import com.wsid.solution.dto.StartSolutionRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * SOL-06 상담 사진 입력. 훈련용 TRN-05에 있던 파일 검증을 옮겨 왔어요.
 * <ol>
 *   <li>PNG·JPEG만, 최대 3장, 장당 5MB (파일 내용의 시그니처로 형식 확인)</li>
 *   <li>상담 소유권·중복·상태·버전 확인 뒤 AI-02 OCR 호출 (트랜잭션 밖)</li>
 *   <li>읽은 글자는 SOL-03 텍스트처럼 민감정보 확인 → AI-06 사실 추출 → 병합·카드 선택</li>
 * </ol>
 * 원본 이미지와 읽은 글자는 DB·디스크·로그에 남기지 않아요.
 */
@Service
public class SolutionImageService {
    static final int MAX_IMAGES = 3;
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(SolutionImageService.class);

    private final AiClient ai;
    private final SendSolutionMessageService messages;

    public SolutionImageService(AiClient ai, SendSolutionMessageService messages) {
        this.ai = ai;
        this.messages = messages;
    }

    public SolutionMessageResponse send(long id, UUID requestId, int stateVersion, List<MultipartFile> files,
            Supplier<Optional<SolutionOwner>> owner) {
        List<OcrImage> images = validate(files);
        return messages.sendRecognizedText(id, requestId, stateVersion, () -> recognize(images, requestId), owner);
    }

    /** 형식·장수·크기를 AI 호출 전에 확인해요. 원래 파일 이름은 AI 서비스로 보내지 않아요. */
    static List<OcrImage> validate(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) throw new ApiException(ErrorCode.INVALID_REQUEST);
        if (files.size() > MAX_IMAGES) throw new ApiException(ErrorCode.FILE_TOO_LARGE);
        List<OcrImage> images = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) throw new ApiException(ErrorCode.INVALID_REQUEST);
            if (file.getSize() > MAX_IMAGE_BYTES) throw new ApiException(ErrorCode.FILE_TOO_LARGE);
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException e) {
                throw new ApiException(ErrorCode.INVALID_REQUEST);
            }
            if (bytes.length > MAX_IMAGE_BYTES) throw new ApiException(ErrorCode.FILE_TOO_LARGE);
            images.add(switch (imageType(bytes)) {
                case PNG -> new OcrImage("image.png", "image/png", bytes);
                case JPEG -> new OcrImage("image.jpg", "image/jpeg", bytes);
            });
        }
        return images;
    }

    /**
     * AI-02 결과를 상담 입력 글자로 바꿔요.
     * <ul>
     *   <li>OCR_FAILED·빈 글자 → OCR_FAILED (직접 입력 안내)</li>
     *   <li>민감정보로 usable=false → SENSITIVE_DATA_DETECTED (SOL-03 텍스트와 같은 정책)</li>
     *   <li>AI 서비스 장애 → AI_SERVICE_UNAVAILABLE</li>
     * </ul>
     */
    String recognize(List<OcrImage> images, UUID requestId) {
        OcrExtractResult result;
        try {
            result = ai.extractText(images);
        } catch (AiUnavailableException e) {
            log.info("SOL-06 AI-02 사용 불가 requestId={}", requestId);
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        if (result.textUsable()) {
            String text = result.recognizedText().strip();
            if (text.length() > StartSolutionRequest.MAX_DESCRIPTION_LENGTH) throw new ApiException(ErrorCode.OCR_FAILED);
            return text;
        }
        if (result.recognizedText() == null) throw new ApiException(ErrorCode.OCR_FAILED);
        // FastAPI는 민감정보가 보이면 글자를 비우고 usable=false로 돌려줘요.
        throw new ApiException(result.usable() ? ErrorCode.OCR_FAILED : ErrorCode.SENSITIVE_DATA_DETECTED);
    }

    private enum ImageType { PNG, JPEG }

    private static ImageType imageType(byte[] data) {
        if (data.length >= 8 && (data[0] & 255) == 137 && data[1] == 80 && data[2] == 78 && data[3] == 71
                && data[4] == 13 && data[5] == 10 && data[6] == 26 && data[7] == 10) return ImageType.PNG;
        if (data.length >= 3 && (data[0] & 255) == 255 && (data[1] & 255) == 216 && (data[2] & 255) == 255) {
            return ImageType.JPEG;
        }
        throw new ApiException(ErrorCode.INVALID_REQUEST);
    }
}
