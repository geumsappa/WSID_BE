package com.wsid.training.domain;

/**
 * 사용자가 한 턴에 보인 대응 행동 (SIM-008). AI가 분류하고 리포트의 근거가 돼요.
 * training_messages.action_code에 이름 그대로 저장해요.
 */
public enum UserAction {
    ASK_QUESTION,
    REFUSE,
    VERIFY_OFFICIAL_CHANNEL,
    END_CONTACT_AND_VERIFY,
    COMPLY,
    UNCLEAR;

    /** AI가 허용 목록 밖의 값을 보내도 흐름이 깨지지 않도록 UNCLEAR로 받아요. */
    public static UserAction fromCode(String code) {
        if (code == null) {
            return UNCLEAR;
        }
        for (UserAction action : values()) {
            if (action.name().equals(code)) {
                return action;
            }
        }
        return UNCLEAR;
    }
}
