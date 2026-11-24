package com.wsid.training.domain;

import com.wsid.training.dto.TrainingResponses;
import java.util.Arrays;
import java.util.List;

/** Fixed virtual materials only. No user files, URLs or image bytes are accepted. */
public enum MockPhoto {
    // 채팅 속 선택지 이름. 몰입을 위해 '가상'이라는 말은 빼요 (실제 사진은 쓰지 않는다는 안내는 사이드바·동의 화면에 있어요)
    ID_CARD("SEND_MOCK_ID_CARD", "신분증 사진 보내기", "신분증 사진"),
    BANKBOOK("SEND_MOCK_BANKBOOK", "통장 사진 보내기", "통장 사진"),
    PHOTO("SEND_MOCK_PHOTO", "사진 보내기", "사진");

    private final String code;
    private final String label;
    private final String subject;

    MockPhoto(String code, String label, String subject) {
        this.code = code; this.label = label; this.subject = subject;
    }
    public String code() { return code; }
    public String subject() { return subject; }
    public String eventText() { return "[가상 " + subject + " 제공]"; }
    public TrainingResponses.MockAttachment attachment() {
        return new TrainingResponses.MockAttachment(name(), subject);
    }
    public TrainingResponses.Option option() {
        return new TrainingResponses.Option(code, label, attachment());
    }
    public static MockPhoto fromCode(String code) {
        return Arrays.stream(values()).filter(photo -> photo.code.equals(code)).findFirst().orElse(null);
    }
    public static List<MockPhoto> offered(String text) {
        if (text == null || text.matches("(?s).*(보내지|제공하지|첨부하지|요구하지|필요 없|필요없|요청하지).*")) return List.of();
        if (!text.matches("(?s).*(보내|제출|첨부|전송|올려|보여|제공|찍어|필요|부탁).*")) return List.of();
        var photos = new java.util.ArrayList<MockPhoto>();
        if (text.matches("(?s).*(신분증|주민등록증|운전면허|여권).*")) photos.add(ID_CARD);
        if (text.contains("통장")) photos.add(BANKBOOK);
        if (photos.isEmpty() && text.matches("(?s).*(사진|이미지|캡처|스크린샷).*")) photos.add(PHOTO);
        return List.copyOf(photos);
    }
}
