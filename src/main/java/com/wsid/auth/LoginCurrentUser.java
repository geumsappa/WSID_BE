package com.wsid.auth;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** local 이외의 프로필에서 쓰는 구현. 로그인 세션에서 사용자 ID를 꺼내요. */
@Component
@Profile("!local")
public class LoginCurrentUser implements CurrentUser {

    @Override
    public long id() {
        // TODO(AUTH-002): Spring Security의 로그인 세션에서 사용자 ID를 꺼내요.
        throw new ApiException(ErrorCode.LOGIN_REQUIRED);
    }
}
