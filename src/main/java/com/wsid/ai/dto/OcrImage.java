package com.wsid.ai.dto;

import java.util.Objects;

/**
 * AI-02(OCR)로 보낼 이미지 한 장. 원본은 메모리에서만 다루고 DB·디스크·로그에 남기지 않아요.
 * 파일 형식·크기·장수 제한은 업로드를 받는 API(TRN-05)에서 먼저 검증해요.
 *
 * @param filename    원본 파일 이름. 없으면 기본 이름을 써요.
 * @param contentType MIME 형식 (예: image/png). 없으면 application/octet-stream으로 보내요.
 * @param content     이미지 바이트
 */
public record OcrImage(String filename, String contentType, byte[] content) {

    public OcrImage {
        Objects.requireNonNull(content, "content");
    }

    /** 바이트 내용은 출력하지 않아요. */
    @Override
    public String toString() {
        return "OcrImage[filename=" + filename + ", contentType=" + contentType + ", size=" + content.length + "]";
    }
}
