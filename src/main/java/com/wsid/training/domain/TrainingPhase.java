package com.wsid.training.domain;

/**
 * 훈련 진행 단계. training_sessions.phase (기본값 DISCOVERY), training_messages.phase
 * 프론트는 이 값을 보고 화면을 바꿔요. 값 이름은 API 명세와 맞춰 확정해요.
 */
public enum TrainingPhase {
    /** 일상 대화로 훈련 소재를 찾는 단계 (상황 대화) */
    DISCOVERY,
    /** 가상 인물과 대화하는 단계 (가상 훈련) */
    SIMULATION,
    /** 훈련이 끝나 리포트를 볼 수 있는 단계 */
    REPORT
}
