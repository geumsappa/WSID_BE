package com.wsid.solution.repository;

import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.domain.SolutionSession;
import java.time.Instant;

/** SOL 쓰기 경계. 사실 수정은 기대 버전 CAS, 종료는 최신 ACTIVE 상태에 적용해요. */
public interface SolutionSessionUpdates {
    int compareAndSet(long id, SolutionOwner owner, int expectedVersion, SolutionSession.Update update);

    int compareAndSetAssist(long id, SolutionOwner owner, int expectedVersion, SolutionSession.Update update, String pendingJson);

    /** 기대 버전 입력 없이 ACTIVE만 한 번 종료. 기존 사실·카드·메시지는 보존해요. */
    int completeActive(long id, SolutionOwner owner, Instant now);
}
