package com.wsid.auth;

import com.wsid.auth.domain.User;
import com.wsid.auth.repository.UserRepository;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬 개발용. 로그인 기능이 생기기 전까지 개발용 계정 하나를 자동으로 만들어 그 사용자로 동작해요.
 * local 프로필에서만 켜져요.
 */
@Component
@Profile("local")
public class LocalDevCurrentUser implements CurrentUser {

    static final String DEV_EMAIL = "dev@wsid.local";

    private final UserRepository users;
    private volatile Long cachedId;

    public LocalDevCurrentUser(UserRepository users) {
        this.users = users;
    }

    @Override
    public long id() {
        Long id = cachedId;
        if (id == null) {
            id = users.findByEmail(DEV_EMAIL)
                    .orElseGet(() -> users.save(User.localDev(DEV_EMAIL, Instant.now())))
                    .getId();
            cachedId = id;
        }
        return id;
    }
}
