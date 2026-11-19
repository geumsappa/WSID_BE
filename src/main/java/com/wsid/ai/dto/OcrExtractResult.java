package com.wsid.ai.dto;

/**
 * AI-02(OCR) 응답의 data.
 *
 * @param recognizedText 이미지에서 읽은 문자. FastAPI는 민감정보를 가리지 않고 원문을 보내요(Gemini에 보낼 때만 가림).
 * @param usable         FastAPI의 민감정보 검사 결과. 상담 사진 입력(SOL-06)은 이 값으로 거부하지 않아요.
 * @param imageType      사진 분류. 분류 실패나 모르는 값은 OTHER예요.
 * @param summary        사진 요약. 분류 실패 시 null이에요.
 * 원문과 요약은 DB·로그에 남기지 않아요.
 */
public record OcrExtractResult(String recognizedText, boolean usable, OcrImageType imageType, String summary) {
    public static final int MAX_SUMMARY_LENGTH = 300;

    public OcrExtractResult {
        if (imageType == null) imageType = OcrImageType.OTHER;
        if (summary != null && summary.isBlank()) summary = null;
        if (summary != null && summary.length() > MAX_SUMMARY_LENGTH) {
            throw new IllegalArgumentException("OCR 요약이 너무 길어요.");
        }
    }

    /** 분류 정보가 없던 기존 호출과 테스트용이에요. */
    public OcrExtractResult(String recognizedText, boolean usable) {
        this(recognizedText, usable, OcrImageType.OTHER, null);
    }

    /** OCR_FAILED를 받았을 때 쓰는 결과. 호출한 쪽은 "이미지를 읽지 못했어요" 흐름으로 이어가요. */
    public static OcrExtractResult failed() {
        return new OcrExtractResult(null, false);
    }

    /** 읽은 글자가 있는지. 민감정보 여부(usable)와는 따로 봐요. */
    public boolean hasRecognizedText() {
        return recognizedText != null && !recognizedText.isBlank();
    }

    /** 민감정보 검사까지 통과한 글자인지. 훈련 등 기존 정책을 쓰는 호출자용이에요. */
    public boolean textUsable() {
        return usable && hasRecognizedText();
    }

    /** 인식한 문자·요약에 민감정보가 섞일 수 있어 로그에 본문을 남기지 않아요. */
    @Override
    public String toString() {
        return "OcrExtractResult[usable=" + usable + ", imageType=" + imageType
                + ", textLength=" + (recognizedText == null ? 0 : recognizedText.length())
                + ", summaryLength=" + (summary == null ? 0 : summary.length()) + "]";
    }
}
