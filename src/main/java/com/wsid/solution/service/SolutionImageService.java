package com.wsid.solution.service;

import com.wsid.ai.AiClient;
import com.wsid.ai.AiUnavailableException;
import com.wsid.ai.dto.OcrExtractResult;
import com.wsid.ai.dto.OcrImage;
import com.wsid.ai.dto.OcrImageType;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.domain.SolutionStatus;
import com.wsid.solution.repository.SolutionSessionRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * SOL-06 상담 사진 입력. 훈련용 TRN-05에 있던 파일 검증을 옮겨 왔어요.
 * <ol>
 *   <li>PNG·JPEG만, 최대 3장, 장당 5MB (파일 내용의 시그니처로 형식 확인)</li>
 *   <li>상담 소유권·중복·상태·버전 확인 뒤 AI-02 OCR 호출 (트랜잭션 밖)</li>
 *   <li>사진 종류·요약·읽은 글자를 합쳐 AI-06 사실 추출 → 병합·카드 선택 (민감정보 확인은 사진만 건너뜀)</li>
 *   <li>사실 저장 뒤 대응 안내(assist)를 한 번 요청. 실패하면 사실과 카드만 응답</li>
 * </ol>
 * 원본 이미지·읽은 글자·요약은 DB·디스크·로그에 남기지 않아요. 대화 기록에는 "사진(송금 내역)을 제출했습니다."만 남아요.
 */
@Service
public class SolutionImageService {
    static final int MAX_IMAGES = 3;
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(SolutionImageService.class);

    private final AiClient ai;
    private final SendSolutionMessageService messages;
    private final SolutionImageAdviceService advice;
    private final SolutionSessionRepository sessions;
    private final TransactionTemplate read;

    public SolutionImageService(AiClient ai, SendSolutionMessageService messages, SolutionImageAdviceService advice,
            SolutionSessionRepository sessions, PlatformTransactionManager manager) {
        this.ai = ai;
        this.messages = messages;
        this.advice = advice;
        this.sessions = sessions;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private SolutionFollowUpAdviceService followUp;

    /** 있으면 사진 내용·앞 대화를 history로 넘겨 안내를 받고 AI 행으로 저장해요. 없으면 예전 요약 안내만 해요. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFollowUp(SolutionFollowUpAdviceService followUp) { this.followUp = followUp; }

    public SolutionMessageResponse send(long id, UUID requestId, int stateVersion, List<MultipartFile> files,
            Supplier<Optional<SolutionOwner>> owner) {
        List<OcrImage> images = validate(files);
        var read = new java.util.concurrent.atomic.AtomicReference<OcrExtractResult>();
        SolutionMessageResponse saved = messages.sendRecognizedText(id, requestId, stateVersion, () -> {
            OcrExtractResult result = ocr(images, requestId);
            read.set(result);
            return photo(result);
        }, owner);
        // 여기부터는 사실·카드가 이미 커밋된 상태예요. 안내는 덧붙이기만 하고 실패해도 저장 결과를 돌려줘요.
        OcrExtractResult ocr = read.get(); // 저장까지 성공했다면 OCR은 이미 실행됐어요. 방어적으로 null도 받아요.
        OcrImageType type = ocr == null ? OcrImageType.OTHER : ocr.imageType();
        if (followUp != null) {
            // 상태 확인·안내·AI 행 저장까지 followUp이 맡아요. 안내를 못 만들면 저장 결과만 돌려줘요.
            var resolved = owner.get();
            var assistant = resolved.isEmpty() ? null
                    : followUp.afterPhoto(id, resolved.get(), saved.stateVersion(), type, requestId);
            return assistant == null ? saved : new SolutionMessageResponse(saved.facts(), saved.responseCards(),
                    saved.nextQuestion(), saved.stateVersion(), assistant);
        }
        var assistant = advice.optionalAdvice(saved.facts(), type, ocr == null ? null : ocr.summary(), requestId);
        if (assistant == null || !stillCurrent(id, saved.stateVersion(), owner)) return saved;
        return new SolutionMessageResponse(saved.facts(), saved.responseCards(), saved.nextQuestion(),
                saved.stateVersion(), assistant);
    }

    /** 안내를 기다리는 사이 다른 요청이 상태를 바꿨거나 상담이 끝났다면 오래된 안내를 붙이지 않아요. */
    private boolean stillCurrent(long id, int stateVersion, Supplier<Optional<SolutionOwner>> owner) {
        Optional<SolutionOwner> resolved = owner.get();
        if (resolved.isEmpty()) return false;
        return Boolean.TRUE.equals(read.execute(tx -> sessions.findOwned(id, resolved.get())
                .filter(s -> s.getStatus() == SolutionStatus.ACTIVE && s.getStateVersion() == stateVersion)
                .isPresent()));
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
     * AI-02 결과를 상담 입력으로 바꿔요.
     * <ul>
     *   <li>OCR_FAILED·빈 글자 → OCR_FAILED (직접 입력 안내)</li>
     *   <li>usable=false(민감정보 감지)는 거부하지 않아요. 사진에는 계좌·전화번호가 찍히는 게 보통이에요.</li>
     *   <li>AI 서비스 장애 → AI_SERVICE_UNAVAILABLE</li>
     * </ul>
     */
    SendSolutionMessageService.RecognizedPhoto recognize(List<OcrImage> images, UUID requestId) {
        return photo(ocr(images, requestId));
    }

    private OcrExtractResult ocr(List<OcrImage> images, UUID requestId) {
        OcrExtractResult result;
        try {
            result = ai.extractText(images);
        } catch (AiUnavailableException e) {
            log.info("SOL-06 AI-02 사용 불가 requestId={}", requestId);
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        if (result == null || !result.hasRecognizedText()) throw new ApiException(ErrorCode.OCR_FAILED);
        return result;
    }

    private static SendSolutionMessageService.RecognizedPhoto photo(OcrExtractResult result) {
        return new SendSolutionMessageService.RecognizedPhoto(extractInput(result), userMessage(result.imageType()),
                assistContext(result));
    }

    /** 사진 턴 history에 넣는 가린 글자 최대 길이. 요약(300자)과 합쳐 history 한 턴(2000자) 안에 들어가요. */
    static final int MAX_PHOTO_TEXT = 600;

    /**
     * 이후 상담 질문의 history로 보낼 사진 내용. FastAPI는 "[사진"으로 시작하는 USER 턴을 사진 자료로 다뤄요.
     * 원문(recognizedText)은 쓰지 않고 FastAPI가 가린 maskedText만 써요. 요약과 가린 글자가 모두 없으면 null이에요.
     */
    static String assistContext(OcrExtractResult result) {
        String summary = result.summary() == null ? null : result.summary().strip();
        String masked = result.maskedText() == null ? null : result.maskedText().strip();
        if (summary == null && masked == null) return null;
        StringBuilder text = new StringBuilder("[사진 · ").append(result.imageType().label()).append("]");
        if (summary != null) text.append(" 요약: ").append(summary);
        if (masked != null) {
            int end = Math.min(masked.length(), MAX_PHOTO_TEXT);
            if (end < masked.length() && Character.isHighSurrogate(masked.charAt(end - 1))) end--;
            text.append("\n사진 속 글자: ").append(masked, 0, end);
        }
        return text.toString();
    }

    /** AI-06에 보낼 글자. 종류 + 요약 + 인식 문자를 합치고, 공개 입력과 같은 1000자 제한에 맞춰 뒤를 잘라요. */
    static String extractInput(OcrExtractResult result) {
        StringBuilder head = new StringBuilder("사진 종류: ").append(result.imageType().label()).append('\n');
        if (result.summary() != null) head.append("요약: ").append(result.summary().strip()).append('\n');
        head.append("인식 문자: ");
        String text = result.recognizedText().strip();
        int room = StartSolutionRequest.MAX_DESCRIPTION_LENGTH - head.length();
        if (room <= 0) throw new ApiException(ErrorCode.OCR_FAILED); // 요약 300자 제한으로 실제로는 생기지 않아요.
        int end = Math.min(text.length(), room);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return head.append(text, 0, end).toString();
    }

    /** 대화 기록용 고정 문구. 사진 내용은 넣지 않아요. */
    static String userMessage(OcrImageType type) {
        return "사진(" + type.label() + ")을 제출했습니다.";
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
