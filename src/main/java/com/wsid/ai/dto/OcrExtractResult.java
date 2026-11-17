package com.wsid.ai.dto;

/**
 * AI-02(OCR) 응답의 data. 문자 추출 결과와 민감정보 검사 결과예요.
 *
 * @param recognizedText 이미지에서 읽은 문자. 민감정보가 걸렸거나 읽지 못했으면 비어 있을 수 있어요.
 * @param usable         민감정보 검사를 통과해 상황 파악 맥락으로 써도 되는지.
 *                       false면 recognizedText를 저장하거나 다음 AI 요청에 넣지 않아요.
 */
public record OcrExtractResult(String recognizedText, boolean usable) {

    /** OCR_FAILED를 받았을 때 쓰는 결과. 호출한 쪽은 "이미지를 읽지 못했어요" 흐름으로 이어가요. */
    public static OcrExtractResult failed() {
        return new OcrExtractResult(null, false);
    }

    /** 다음 단계에 넘겨도 되는 문자가 있는지. AI가 usable=true라고 해도 빈 문자는 쓰지 않아요. */
    public boolean textUsable() {
        return usable && recognizedText != null && !recognizedText.isBlank();
    }

    /** 인식한 문자에 민감정보가 섞일 수 있어 로그에 본문을 남기지 않아요. */
    @Override
    public String toString() {
        int length = recognizedText == null ? 0 : recognizedText.length();
        return "OcrExtractResult[usable=" + usable + ", textLength=" + length + "]";
    }
}
