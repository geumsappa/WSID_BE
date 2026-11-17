package com.wsid.solution.service;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.domain.SolutionSession;
import com.wsid.solution.domain.SolutionStatus;
import com.wsid.solution.dto.CompleteSolutionResponse;
import com.wsid.solution.repository.SolutionSessionRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** SOL-05: 최신 본인 ACTIVE 상담을 종료. 반복 종료는 시간·버전 변경 없이 200이에요. */
@Service
public class CompleteSolutionService {
    private static final Logger log = LoggerFactory.getLogger(CompleteSolutionService.class);
    private final SolutionSessionRepository sessions;
    private final TransactionTemplate write;
    private final Clock clock;

    @Autowired
    public CompleteSolutionService(SolutionSessionRepository sessions, PlatformTransactionManager manager) {
        this(sessions, manager, Clock.systemUTC());
    }

    CompleteSolutionService(SolutionSessionRepository sessions, PlatformTransactionManager manager, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompleteSolutionResponse complete(long id, Supplier<Optional<SolutionOwner>> owner) {
        SolutionOwner resolved = owner.get().orElseThrow(() -> new ApiException(ErrorCode.SOLUTION_NOT_FOUND));
        Result result = write.execute(tx -> {
            SolutionSession before = owned(id, resolved);
            if (before.getStatus() == SolutionStatus.COMPLETED) return new Result(response(id), false);
            if (before.getStatus() != SolutionStatus.ACTIVE) throw invalidState();
            int rows = sessions.completeActive(id, resolved, clock.instant());
            if (rows != 0 && rows != 1) throw invalidState();
            // READ COMMITTED의 새 statement + custom repository의 clear로 0행 경쟁 결과도 확인해요.
            if (owned(id, resolved).getStatus() != SolutionStatus.COMPLETED) throw invalidState();
            return new Result(response(id), rows == 1);
        });
        // 커밋에 실패한 종료를 성공 로그로 남기지 않아요. 소유자·본문·토큰은 출력하지 않아요.
        log.info("SOL-05 상담 종료 solutionId={} transitioned={}", id, result.transitioned());
        return result.response();
    }

    private SolutionSession owned(long id, SolutionOwner owner) {
        return sessions.findOwned(id, owner).orElseThrow(() -> new ApiException(ErrorCode.SOLUTION_NOT_FOUND));
    }

    private static CompleteSolutionResponse response(long id) {
        return new CompleteSolutionResponse(id, SolutionStatus.COMPLETED);
    }

    private static IllegalStateException invalidState() {
        return new IllegalStateException("상담 종료 결과를 확인할 수 없어요.");
    }

    private record Result(CompleteSolutionResponse response, boolean transitioned) {}
}
