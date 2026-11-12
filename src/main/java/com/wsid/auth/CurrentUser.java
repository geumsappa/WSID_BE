package com.wsid.auth;

/**
 * 지금 요청을 보낸 사용자. 서비스 계층은 이 인터페이스로만 사용자 ID를 얻어요.
 * 세션과 리포트의 소유권 확인은 모두 이 ID를 기준으로 해요.
 */
public interface CurrentUser {

    /** @throws com.wsid.common.error.ApiException 로그인하지 않았을 때 (LOGIN_REQUIRED) */
    long id();
}
