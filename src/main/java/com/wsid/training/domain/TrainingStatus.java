package com.wsid.training.domain;

/** training_sessions.status (VARCHAR(20), 기본값 ACTIVE) */
public enum TrainingStatus {
    ACTIVE,
    COMPLETED,
    /** 오래 활동이 없어 만료됨 */
    EXPIRED
    // TODO: 실제 피해 대응으로 전환했을 때의 일시 중지 상태
}
