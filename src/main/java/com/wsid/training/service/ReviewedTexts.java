package com.wsid.training.service;

/**
 * 팀이 검토한 문구.
 * TODO(content): resources/content/scenarios/*.yml에서 읽어 오도록 바꾸면 이 클래스는 지워요.
 */
final class ReviewedTexts {

    static final String DISCOVERY_FIRST_QUESTION = "최근에 어디 다녀오셨어요? 집에서 한 일을 이야기해도 좋아요.";
    static final String SCENARIO_OPENING = "안녕하세요, 올리신 물건 보고 연락드렸어요. 아직 판매 중인가요?";
    static final String KEY_SCENE = "입금했는데 확인이 안 되네요. 제가 보낸 링크에서 결제 확인을 눌러 주시겠어요?";
    static final String FALLBACK_LINE = "네, 확인해 볼게요. 조금만 기다려 주세요.";
    static final String FINISHED = "훈련이 끝났어요. 결과 리포트를 확인해 보세요.";

    private ReviewedTexts() {
    }
}
