package com.wsid.solution.service;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.domain.SolutionSession;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상담 삭제. 긴급 대응 화면의 '저장 없이 나가기'와 마이페이지 '긴급 대응 기록' 삭제에서 써요.
 * 본인(회원 또는 같은 브라우저의 비회원) 상담만 지울 수 있고, 남의 상담·없는 상담은 같은 404예요.
 */
@Service
public class DeleteSolutionService {
    private static final Logger log = LoggerFactory.getLogger(DeleteSolutionService.class);
    private final SolutionSessionRepository sessions;
    private final SolutionMessageRepository messages;

    public DeleteSolutionService(SolutionSessionRepository sessions, SolutionMessageRepository messages) {
        this.sessions = sessions;
        this.messages = messages;
    }

    @Transactional
    public void delete(long id, Supplier<Optional<SolutionOwner>> owner) {
        SolutionOwner resolved = owner.get().orElseThrow(() -> new ApiException(ErrorCode.SOLUTION_NOT_FOUND));
        SolutionSession session = sessions.findOwned(id, resolved)
                .orElseThrow(() -> new ApiException(ErrorCode.SOLUTION_NOT_FOUND));
        int removed = messages.deleteAllOfSolution(session.getId());
        sessions.deleteById(session.getId());
        // 소유자·본문은 남기지 않아요
        log.info("상담 삭제 solutionId={} messages={}", id, removed);
    }
}
