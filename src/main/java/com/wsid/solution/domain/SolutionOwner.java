package com.wsid.solution.domain;

import java.util.UUID;

/** 상담 소유자. 회원 ID와 서버가 만든 익명 UUID 중 정확히 하나만 가져요. */
public record SolutionOwner(Long userId, UUID anonymousId) {
    public SolutionOwner {
        if ((userId == null) == (anonymousId == null)) {
            throw new IllegalArgumentException("상담 소유자는 회원 또는 익명 중 하나여야 해요.");
        }
    }

    public static SolutionOwner member(long userId) {
        return new SolutionOwner(userId, null);
    }

    public static SolutionOwner anonymous(UUID anonymousId) {
        return new SolutionOwner(null, anonymousId);
    }

    public boolean isMember() {
        return userId != null;
    }

    /** 소유자 값은 로그·오류 메시지에 남기지 않아요. */
    @Override
    public String toString() {
        return isMember() ? "SolutionOwner[member]" : "SolutionOwner[anonymous]";
    }
}
