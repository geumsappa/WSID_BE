package com.wsid.auth.domain;

/** users.status (VARCHAR(20), 기본값 PENDING) */
public enum UserStatus {
    /** 가입 신청만 하고 이메일 인증을 마치지 않음 */
    PENDING,
    /** 이메일 인증 완료. 로그인 가능 */
    ACTIVE
}
