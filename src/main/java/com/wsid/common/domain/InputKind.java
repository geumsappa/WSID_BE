package com.wsid.common.domain;

/**
 * 입력 종류. training_messages.input_kind, solution_messages.input_kind (VARCHAR(15), 기본값 TEXT)
 * TEXT 외의 값 이름은 API 명세와 맞춰 확정해요.
 */
public enum InputKind {
    /** 글로 입력 */
    TEXT,
    /** 화면의 선택지를 고름 */
    OPTION,
    /** 모의 페이지에서 한 행동 (링크 열기, 페이지 닫기 등) */
    ACTION
}
