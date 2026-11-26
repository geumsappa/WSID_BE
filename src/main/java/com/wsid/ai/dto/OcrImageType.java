package com.wsid.ai.dto;

/** AI-02 사진 분류. 모르는 값이나 분류 실패(null)는 OTHER로 받아 사진 입력 자체는 실패시키지 않아요. */
public enum OcrImageType {
    SMS("문자 메시지"),
    MESSENGER_CHAT("메신저 대화"),
    TRANSFER_RECEIPT("송금 내역"),
    WEB_PAGE("웹페이지"),
    OTHER("기타 자료");

    private final String label;

    OcrImageType(String label) {
        this.label = label;
    }

    /** 대화 기록과 사실 추출 입력에 쓰는 한국어 이름. */
    public String label() {
        return label;
    }

    public static OcrImageType from(String value) {
        if (value == null) return OTHER;
        for (OcrImageType type : values()) {
            if (type.name().equals(value)) return type;
        }
        return OTHER;
    }
}
