package com.wsid.solution.repository;

import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.domain.SolutionSession;

/** SOL의 모든 후속 쓰기는 이 버전 조건부 갱신 규칙을 따라야 해요. */
public interface SolutionSessionUpdates {
    int compareAndSet(long id, SolutionOwner owner, int expectedVersion, SolutionSession.Update update);
}
