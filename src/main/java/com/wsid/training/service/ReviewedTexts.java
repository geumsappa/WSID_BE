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

    static final String DISCOVERY_ACTIVITY = "최근에 했던 일 중 하나를 조금 더 자세히 알려주실래요? 물건을 사고팔았거나, 택배를 기다렸거나, 일자리를 알아본 일처럼요.";
    static final String DISCOVERY_ROLE = "그 일에서 어떤 쪽이셨어요? 파는 쪽, 사는 쪽, 받는 쪽처럼 알려주세요.";
    static final String DISCOVERY_CHANNEL = "그때 상대와는 어떻게 연락하셨어요? 문자, 전화, 앱 채팅 같은 것 중에요.";
    static final String DISCOVERY_ITEM = "그때 주고받은 물건이나 이용한 서비스가 있었나요?";
    static final String DISCOVERY_OTHER = "그때 기억에 남는 일이 있으면 조금 더 이야기해 주세요.";
    static final String DISCOVERY_READY = "이제 연습을 시작할게요.";
    static final String DISCOVERY_DEFAULT = "충분한 정보를 모으지 못해 기본 상황으로 연습해요.";

    private ReviewedTexts() {
    }
}
