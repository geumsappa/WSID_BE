package com.wsid.solution.service;

import com.wsid.ai.TrainingAiGateway;
import com.wsid.content.GuideCatalog;
import com.wsid.solution.domain.SolutionMessage;
import com.wsid.solution.domain.SolutionStatus;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import java.time.Instant;
import com.wsid.solution.dto.SolutionMessageResponse;
import com.wsid.solution.dto.StartSolutionResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI-08 긴급 대응 도우미 말. 사실이 정리되거나 바뀐 뒤, 확인된 사실과 검토된 대응 카드(제목·할 일)만 AI에 보내
 * "지금 할 일" 안내 문장을 받아요. 예전에는 프론트가 규칙으로 조립하던 말이에요.
 * 실패하거나 이상한 문장이면 null을 돌려주고, 화면은 예전 규칙 문장을 대신 보여 줘요 (상담 흐름은 막지 않음).
 * DB 트랜잭션 밖(컨트롤러)에서 불러서 AI를 기다리는 동안 DB 연결을 잡고 있지 않아요.
 */
@Service
public class SolutionReplyService {
    private static final Logger log = LoggerFactory.getLogger(SolutionReplyService.class);
    private static final int MAX_LENGTH = 500;
    // 연락처·링크·계좌처럼 보이는 값이 있으면 쓰지 않아요 (검토된 연락처는 카드 화면에만 있어요)
    private static final Pattern UNSAFE = Pattern.compile(
            "(?i)https?://|www\\.|(?<!\\d)\\d{2,4}[- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)|(?<!\\d)1[568]\\d{2}[-. ]?\\d{4}(?!\\d)|(?<!\\d)\\d{9,}(?!\\d)");

    private final TrainingAiGateway ai;
    private final GuideCatalog guides;
    private final SolutionSessionRepository sessions;
    private final SolutionMessageRepository messages;
    private final TransactionTemplate write;

    @Autowired
    public SolutionReplyService(TrainingAiGateway ai, GuideCatalog guides, SolutionSessionRepository sessions,
            SolutionMessageRepository messages, PlatformTransactionManager manager) {
        this.ai = ai;
        this.guides = guides;
        this.sessions = sessions;
        this.messages = messages;
        this.write = manager == null ? null : new TransactionTemplate(manager);
    }

    /** 저장 없이 도우미 말만 만들어요 (단위 테스트용) */
    SolutionReplyService(TrainingAiGateway ai, GuideCatalog guides) {
        this(ai, guides, null, null, null);
    }

    public StartSolutionResponse withReply(StartSolutionResponse response, String userText) {
        String text = reply(response.facts(), response.responseCards(), userText, "start");
        if (text == null || !save(response.solutionId(), response.stateVersion(), text)) return response;
        return response.withReply(text);
    }

    public SolutionMessageResponse withReply(long solutionId, SolutionMessageResponse response, String userText, String kind) {
        if (response.assistant() != null) return response; // 이미 AI 답변(질문 응답·추가 질문)이 있으면 덧붙이지 않아요
        String text = reply(response.facts(), response.responseCards(), userText, kind);
        if (text == null || !save(solutionId, response.stateVersion(), text)) return response;
        return response.withReply(text);
    }

    /**
     * 도우미 말을 대화 기록에 남겨요. 그래야 새로고침(SOL-02) 때도 같은 대화가 보여요.
     * 기다리는 사이 다른 요청이 상태를 바꿨거나 상담이 끝났으면 오래된 말은 남기지도, 보여 주지도 않아요.
     * 이번 요청이 마지막에 다음 고정 질문(SYSTEM)을 남겼다면 그 질문을 한 칸 뒤로 밀고 도우미 말을 앞에 둬요
     * ("답 → 도우미 말 → 다음 질문" 순서. SolutionFollowUpAdviceService와 같은 방식).
     */
    boolean save(long solutionId, int stateVersion, String text) {
        if (write == null) return true;
        try {
            return Boolean.TRUE.equals(write.execute(tx -> {
                var session = sessions.findById(solutionId).orElse(null);
                if (session == null || session.getStatus() != SolutionStatus.ACTIVE || session.getStateVersion() != stateVersion) {
                    return false;
                }
                int sequence = Math.addExact(messages.lastSequence(solutionId), 1);
                int last = sequence - 1;
                if (last > 0 && messages.moveSystemMessage(solutionId, last, sequence) == 1) sequence = last;
                messages.save(SolutionMessage.aiReply(solutionId, sequence, text, List.of(), Instant.now()));
                messages.flush();
                return true;
            }));
        } catch (DataIntegrityViolationException e) {
            log.info("Solution reply save race solutionId={}", solutionId);
            return false;
        } catch (RuntimeException e) {
            log.warn("Solution reply save failed: type={}", e.getClass().getSimpleName());
            return false;
        }
    }

    String reply(Map<String, String> facts, List<StartSolutionResponse.Card> cards, String userText, String kind) {
        if (ai.isMock()) return null;
        try {
            var codes = new LinkedHashSet<String>();
            cards.forEach(card -> codes.add(card.code()));
            var reviewed = codes.isEmpty() ? List.<GuideCatalog.Card>of() : guides.cards(codes);
            var cardPayload = cards.stream().limit(10).map(card -> Map.<String, Object>of(
                    "title", card.title(), "priority", card.priority() == null ? "" : card.priority(),
                    "actions", reviewed.stream().filter(item -> item.code().equals(card.code())).findFirst()
                            .map(GuideCatalog.Card::actions).orElse(List.of()).stream().limit(5).toList())).toList();
            var payload = new java.util.HashMap<String, Object>();
            payload.put("facts", facts);
            payload.put("cards", cardPayload);
            payload.put("kind", kind);
            if (userText != null && !userText.isBlank()) payload.put("userText", userText.strip().substring(0, Math.min(1000, userText.strip().length())));
            String text = ai.request("/solutions/reply", payload).path("message").asString("").strip();
            if (text.isEmpty() || text.length() > MAX_LENGTH || UNSAFE.matcher(text).find()) return null;
            return text;
        } catch (RuntimeException e) {
            // AI가 실패해도 상담은 계속돼요. 사용자 문장이 섞일 수 있어 예외 내용은 남기지 않아요.
            log.warn("Solution reply unavailable: type={}", e.getClass().getSimpleName());
            return null;
        }
    }
}
