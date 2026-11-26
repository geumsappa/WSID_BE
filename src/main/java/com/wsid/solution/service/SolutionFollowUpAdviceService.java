package com.wsid.solution.service;

import com.wsid.ai.dto.OcrImageType;
import com.wsid.ai.dto.SolutionAssistHistoryTurn;
import com.wsid.solution.domain.FactValue;
import com.wsid.solution.domain.SolutionFact;
import com.wsid.solution.domain.SolutionMessage;
import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.domain.SolutionStatus;
import com.wsid.solution.dto.SolutionAssistant;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사진 업로드(SOL-06)나 선택지 답(SOL-03 CHOICE)으로 사실을 저장한 뒤, 고정 질문만 돌려주지 않고
 * LLM이 "지금 상황에서 무엇을 해야 하는지"를 이어서 안내하게 해요.
 *
 * <ol>
 *   <li>저장이 끝난 상태를 읽어요: 사실, 사기 유형, 저장된 대화(history: 사진·질문·답변).</li>
 *   <li>서버가 만든 질문 문장으로 assist PLAN→승인 근거→ANSWER를 한 번 돌려요(추가 질문 없음).</li>
 *   <li>그사이 다른 요청이 상태를 바꾸지 않았으면 답을 AI 행으로 저장해 새로고침 때도 보이게 해요.</li>
 * </ol>
 * 근거 없음·장애·시간 초과·상태 변경은 모두 null이고, 이미 저장된 사실·카드·다음 질문 응답은 그대로 나가요.
 */
@Service
public class SolutionFollowUpAdviceService {
    private static final Logger log = LoggerFactory.getLogger(SolutionFollowUpAdviceService.class);
    static final String NEXT_STEP = "지금까지 확인된 상황(사진·대화 포함)을 기준으로 지금 해야 할 대응 방법을 안내해 주세요.";

    private final SolutionImageAdviceService advice;
    private final SolutionSessionRepository sessions;
    private final SolutionMessageRepository messages;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    private final Clock clock;
    private final boolean historyEnabled;

    @Autowired
    public SolutionFollowUpAdviceService(SolutionImageAdviceService advice, SolutionSessionRepository sessions,
            SolutionMessageRepository messages, PlatformTransactionManager manager,
            @Value("${wsid.ai.assist-history-enabled:true}") boolean historyEnabled) {
        this(advice, sessions, messages, manager, Clock.systemUTC(), historyEnabled);
    }

    SolutionFollowUpAdviceService(SolutionImageAdviceService advice, SolutionSessionRepository sessions,
            SolutionMessageRepository messages, PlatformTransactionManager manager, Clock clock, boolean historyEnabled) {
        this.advice = advice;
        this.sessions = sessions;
        this.messages = messages;
        this.clock = clock;
        this.historyEnabled = historyEnabled;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 사진 저장 직후. 사진 내용은 history의 사진 턴으로 가고, 질문 문장에는 종류만 넣어요. */
    public SolutionAssistant afterPhoto(long id, SolutionOwner owner, int stateVersion, OcrImageType type, UUID requestId) {
        String label = (type == null ? OcrImageType.OTHER : type).label();
        return followUp(id, owner, stateVersion, "방금 " + label + " 사진을 올렸어요. " + NEXT_STEP, requestId);
    }

    /**
     * 선택지 답 저장 직후. 무엇을 물었고 어떻게 답했는지 넣고, 그 답 때문에 달라지는 할 일을 묻어요.
     * 앞 안내(history의 AI 턴)를 되풀이하지 않게 해서 선택마다 같은 답이 나오지 않게 해요.
     */
    public SolutionAssistant afterChoice(long id, SolutionOwner owner, int stateVersion, SolutionFact fact,
            FactValue value, UUID requestId) {
        String question = "\"" + fact.question() + "\"라는 질문에 \"" + value.label() + "\"라고 답했어요. "
                + "이 답에 따라 지금 해야 할 일이나 조심할 점을 알려 주세요. "
                + "앞에서 이미 안내한 내용은 되풀이하지 말고, 이 답 때문에 새로 필요한 것만 짧게 알려 주세요.";
        return followUp(id, owner, stateVersion, question, requestId);
    }

    private SolutionAssistant followUp(long id, SolutionOwner owner, int stateVersion, String question, UUID requestId) {
        Context context;
        try {
            context = read.execute(tx -> current(id, owner, stateVersion) ? context(id) : null);
        } catch (RuntimeException e) {
            log.info("후속 안내 생략 requestId={} cause={}", requestId, e.getClass().getSimpleName());
            return null;
        }
        if (context == null) return null;
        SolutionAssistant answer = advice.optionalAdvice(context.facts(), question, context.fraudType(),
                context.history(), requestId);
        if (answer == null) return null;
        try {
            // 안내를 기다리는 사이 다른 요청이 상태를 바꿨거나 상담이 끝났다면 오래된 안내를 붙이지도, 남기지도 않아요.
            Boolean saved = write.execute(tx -> {
                if (!current(id, owner, stateVersion)) return false;
                int sequence = Math.addExact(messages.lastSequence(id), 1);
                // 이번 요청이 마지막에 다음 고정 질문(SYSTEM)을 남겼다면, 그 질문을 한 칸 뒤로 밀고 안내를 그 앞에 둬요.
                // 새로고침 때 "답 → 그 답에 대한 안내 → 다음 질문" 순서로 보이게 하기 위해서예요.
                int last = sequence - 1;
                if (messages.moveSystemMessage(id, last, sequence) == 1) sequence = last;
                messages.save(SolutionMessage.aiReply(id, sequence, answer.answer(), answer.referenceCodes(), clock.instant()));
                messages.flush();
                return true;
            });
            return Boolean.TRUE.equals(saved) ? answer : null;
        } catch (DataIntegrityViolationException e) {
            // 같은 순간 다른 요청이 같은 sequence를 차지했어요. 그 요청이 상태를 바꾸므로 이 안내는 버려요.
            log.info("후속 안내 저장 경합 requestId={}", requestId);
            return null;
        }
    }

    private boolean current(long id, SolutionOwner owner, int stateVersion) {
        return sessions.findOwned(id, owner)
                .filter(s -> s.getStatus() == SolutionStatus.ACTIVE && s.getStateVersion() == stateVersion)
                .isPresent();
    }

    private Context context(long id) {
        var session = sessions.findById(id).orElseThrow();
        var history = historyEnabled ? SolutionConversationHistory.from(messages.findBySolutionIdOrderBySequenceNoAsc(id)) : null;
        return new Context(session.readFacts().toKeyMap(), session.getFraudTypeCode(), history);
    }

    private record Context(Map<String, String> facts, String fraudType, List<SolutionAssistHistoryTurn> history) {}
}
