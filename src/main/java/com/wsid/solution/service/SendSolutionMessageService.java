package com.wsid.solution.service;

import com.wsid.ai.AiClient;
import com.wsid.ai.AiUnavailableException;
import com.wsid.ai.dto.SolutionExtractRequest;
import com.wsid.common.domain.InputKind;
import com.wsid.common.domain.MessageSender;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.SendSolutionMessageRequest;
import com.wsid.solution.dto.SolutionMessageResponse;
import com.wsid.solution.dto.StartSolutionResponse;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** SOL-03: 조회 → AI(트랜잭션 밖) → 소유권·버전 재검증 → 원자적 저장. */
@Service
public class SendSolutionMessageService {
    private static final Logger log = LoggerFactory.getLogger(SendSolutionMessageService.class);
    /** SOL-06 사진 입력 때 OCR 전 확인용 고정 문구. 인식한 글자는 저장하지 않아요. */
    static final String IMAGE_RECEIVED = "사진 속 내용을 제출했습니다.";

    /**
     * SOL-06 OCR 결과를 상담 입력으로 바꾼 값. 서버 안에서만 만들어지고 공개 JSON으로는 만들 수 없어요.
     *
     * @param content     AI-06 사실 추출에 보낼 글자(사진 종류 + 요약 + 인식 문자, 1000자 이내). 저장하지 않아요.
     * @param userMessage 대화 기록에 남길 고정 문구. 예: "사진(송금 내역)을 제출했습니다."
     * @param assistContext 이후 상담 질문의 history로 보낼 사진 내용(요약 + 가린 글자). 없으면 null이고 그때는 기존처럼 저장해요.
     */
    public record RecognizedPhoto(String content, String userMessage, String assistContext) {
        public RecognizedPhoto(String content, String userMessage) {
            this(content, userMessage, null);
        }

        @Override
        public String toString() {
            return "RecognizedPhoto[contentLength=" + (content == null ? 0 : content.length())
                    + ", assistContextLength=" + (assistContext == null ? 0 : assistContext.length()) + "]";
        }
    }
    private final AiClient ai;
    private SolutionAssistService assist;
    @Autowired(required = false)
    public void setAssistService(SolutionAssistService assist) { this.assist = assist; }
    private SolutionFollowUpAdviceService followUp;
    /** 있으면 선택지 답을 저장한 뒤 LLM이 그 답과 사진·앞 대화를 보고 할 일을 이어서 안내해요. */
    @Autowired(required = false)
    public void setFollowUp(SolutionFollowUpAdviceService followUp) { this.followUp = followUp; }
    private final SensitiveDescriptionGuard guard;
    private final ResponseCardCatalog cards;
    private final SolutionSessionRepository sessions;
    private final SolutionMessageRepository messages;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    private final Clock clock;

    @Autowired
    public SendSolutionMessageService(AiClient ai, SensitiveDescriptionGuard guard, ResponseCardCatalog cards,
            SolutionSessionRepository sessions, SolutionMessageRepository messages, PlatformTransactionManager manager) {
        this(ai, guard, cards, sessions, messages, manager, Clock.systemUTC());
    }

    SendSolutionMessageService(AiClient ai, SensitiveDescriptionGuard guard, ResponseCardCatalog cards,
            SolutionSessionRepository sessions, SolutionMessageRepository messages,
            PlatformTransactionManager manager, Clock clock) {
        this.ai = ai;
        this.guard = guard;
        this.cards = cards;
        this.sessions = sessions;
        this.messages = messages;
        this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 요청 스레드의 기존 소유자만 확인해요. 호출자의 트랜잭션도 AI 구간에는 유지하지 않아요. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SolutionMessageResponse send(long id, SendSolutionMessageRequest request,
            Supplier<Optional<SolutionOwner>> owner) {
        if (request.purpose() != SendSolutionMessageRequest.Purpose.FACT_UPDATE) {
            if (assist == null) throw new IllegalStateException("assist service is not configured");
            return assist.send(id, request, owner);
        }
        // 텍스트 입력은 원문을 대화 기록에 남겨요(민감정보 패턴은 sendFactUpdate에서 거부). 선택형은 choice()가 따로 남겨요.
        SolutionOwner resolved = existingOwner(owner);
        SolutionMessageResponse saved = sendFactUpdate(id, request, resolved, owner,
                request.isText() ? request.content().strip() : StartSolutionService.DESCRIPTION_RECEIVED, null, true);
        if (request.isText() || followUp == null) return saved;
        // 선택지 답은 이미 커밋됐어요. 고정 질문만 돌려주지 않고 LLM 안내를 덧붙이되, 실패하면 저장 결과만 돌려줘요.
        var assistant = followUp.afterChoice(id, resolved, saved.stateVersion(), request.fact(), request.selected(),
                request.requestId());
        return assistant == null ? saved : new SolutionMessageResponse(saved.facts(), saved.responseCards(),
                saved.nextQuestion(), saved.stateVersion(), assistant);
    }

    /**
     * SOL-06. 소유권·중복·상태·버전을 먼저 확인한 뒤에만 OCR을 불러요. 읽은 글자는 AI-06 사실 추출 →
     * 병합·카드 선택 흐름으로 이어가고, 원문은 저장하지 않아요. 사진은 계좌·전화번호가 찍히는 게 당연해서
     * 민감정보 확인(SensitiveDescriptionGuard)을 건너뛰어요. 일반 채팅 입력(SOL-03)은 계속 검사해요.
     *
     * @param recognize 트랜잭션 밖에서 실행할 OCR. 사용할 수 없으면 ApiException을 던져요.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SolutionMessageResponse sendRecognizedText(long id, UUID requestId, int stateVersion,
            Supplier<RecognizedPhoto> recognize, Supplier<Optional<SolutionOwner>> owner) {
        SolutionOwner resolved = existingOwner(owner);
        SendSolutionMessageRequest probe = SendSolutionMessageRequest.recognizedText(requestId, stateVersion, IMAGE_RECEIVED);
        read.execute(tx -> snapshot(id, resolved, probe));
        RecognizedPhoto photo = recognize.get();
        SendSolutionMessageRequest request =
                SendSolutionMessageRequest.recognizedText(requestId, stateVersion, photo.content());
        return sendFactUpdate(id, request, resolved, owner, photo.userMessage(), photo.assistContext(), false);
    }

    /** @param assistContext 사진 메시지면 history용 사진 내용, 아니면 null. */
    private SolutionMessageResponse sendFactUpdate(long id, SendSolutionMessageRequest request, SolutionOwner resolved,
            Supplier<Optional<SolutionOwner>> owner, String userMessage, String assistContext, boolean checkSensitive) {
        Snapshot snapshot = read.execute(tx -> snapshot(id, resolved, request));
        SolutionFacts extracted = null;
        String fraudType = null;
        if (request.isText()) {
            if (checkSensitive) guard.check(request.content());
            var aiRequest = new SolutionExtractRequest(request.content(), snapshot.facts().knownValues(), request.requestId());
            try {
                var result = ai.extractSolutionFacts(aiRequest);
                extracted = SolutionFacts.fromStrings(result.facts());
                fraudType = result.fraudType();
            } catch (AiUnavailableException e) {
                log.info("SOL-03 AI-06 사용 불가 requestId={}", request.requestId());
                throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE);
            }
        }
        if (!resolved.equals(existingOwner(owner))) throw new ApiException(ErrorCode.SOLUTION_NOT_FOUND);
        SolutionFacts result = extracted;
        String suggestedType = fraudType;
        try {
            return write.execute(tx -> apply(id, resolved, request, result, suggestedType, userMessage, assistContext));
        } catch (ConcurrentUpdate | OptimisticLockingFailureException | PessimisticLockingFailureException e) {
            throw classifyRace(id, resolved, request);
        } catch (DataIntegrityViolationException e) {
            if (!isMessageRace(e)) throw e;
            throw classifyRace(id, resolved, request);
        }
    }

    private Snapshot snapshot(long id, SolutionOwner owner, SendSolutionMessageRequest request) {
        SolutionSession session = checkedSession(id, owner, request);
        SolutionQuestion question = session.readNextQuestion();
        if (!request.isText() && (question == null || !question.factKey().equals(request.fact().key())
                || !question.options().contains(request.selected().name()))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return new Snapshot(session.readFacts(), question);
    }

    private SolutionSession checkedSession(long id, SolutionOwner owner, SendSolutionMessageRequest request) {
        SolutionSession session = sessions.findOwned(id, owner)
                .orElseThrow(() -> new ApiException(ErrorCode.SOLUTION_NOT_FOUND));
        if (messages.existsBySolutionIdAndClientRequestId(id, request.requestId())) {
            throw new ApiException(ErrorCode.DUPLICATE_REQUEST);
        }
        if (session.getStatus() != SolutionStatus.ACTIVE) throw new ApiException(ErrorCode.SOLUTION_FINISHED);
        if (session.getStateVersion() != request.stateVersion()) throw new ApiException(ErrorCode.STATE_VERSION_CONFLICT);
        return session;
    }

    private SolutionMessageResponse apply(long id, SolutionOwner owner, SendSolutionMessageRequest request,
            SolutionFacts extracted, String fraudType, String userMessage, String assistContext) {
        Snapshot before = snapshot(id, owner, request);
        SolutionFacts updated;
        if (request.isText()) {
            SolutionFacts.Merge merged = before.facts().mergeExtracted(extracted);
            updated = merged.facts();
            if (merged.conflictCount() > 0) {
                log.info("SOL-03 기존 사실 유지 requestId={} conflictCount={}", request.requestId(), merged.conflictCount());
            }
        } else {
            updated = before.facts().withChoice(request.fact(), request.selected());
        }
        Set<SolutionFact> skipped = new HashSet<>();
        for (String key : messages.answeredUnknownKeys(id)) {
            skipped.add(SolutionFact.fromKey(key).orElseThrow(() -> new IllegalStateException("저장된 선택 키가 올바르지 않아요.")));
        }
        if (!request.isText() && request.selected() == FactValue.UNKNOWN) skipped.add(request.fact());
        SolutionQuestion question = SolutionQuestion.next(updated, skipped).orElse(null);
        java.util.List<ResponseCard> selectedCards;
        try {
            selectedCards = ResponseRules.cards(updated, cards);
        } catch (ResponseRules.MissingResponseCardException e) {
            log.warn("SOL-03 검토 카드 없음 requestId={} card={}", request.requestId(), e.code());
            throw new ApiException(ErrorCode.RESPONSE_CONTENT_UNAVAILABLE);
        }
        Instant now = clock.instant();
        if (sessions.compareAndSet(id, owner, request.stateVersion(),
                SolutionSession.prepareUpdate(updated, selectedCards, question, fraudType, now)) != 1) throw new ConcurrentUpdate();
        int sequence = Math.addExact(messages.lastSequence(id), 1);
        messages.save(!request.isText()
                ? SolutionMessage.choice(id, sequence, request.fact(), request.selected(), request.requestId(), now)
                : assistContext != null
                        ? SolutionMessage.photo(id, sequence, userMessage, assistContext, request.requestId(), now)
                        : SolutionMessage.of(id, MessageSender.USER, InputKind.TEXT, sequence,
                                userMessage, request.requestId(), now));
        if (question != null && !Objects.equals(question, before.question())) {
            messages.save(SolutionMessage.of(id, MessageSender.SYSTEM, InputKind.TEXT, Math.addExact(sequence, 1),
                    question.content(), null, now));
        }
        messages.flush();
        SolutionSession stored = sessions.findOwned(id, owner)
                .orElseThrow(() -> new IllegalStateException("갱신한 상담을 읽을 수 없어요."));
        return new SolutionMessageResponse(updated.toKeyMap(), selectedCards.stream().map(StartSolutionResponse.Card::from).toList(),
                StartSolutionResponse.Question.from(question), stored.getStateVersion());
    }

    /** 반드시 실패한 저장 트랜잭션이 끝난 뒤 새 트랜잭션에서 확인해요. */
    private ApiException classifyRace(long id, SolutionOwner owner, SendSolutionMessageRequest request) {
        return read.execute(tx -> {
            var session = sessions.findOwned(id, owner);
            if (session.isEmpty()) return new ApiException(ErrorCode.SOLUTION_NOT_FOUND);
            if (messages.existsBySolutionIdAndClientRequestId(id, request.requestId())) return new ApiException(ErrorCode.DUPLICATE_REQUEST);
            if (session.get().getStatus() != SolutionStatus.ACTIVE) return new ApiException(ErrorCode.SOLUTION_FINISHED);
            return new ApiException(ErrorCode.STATE_VERSION_CONFLICT);
        });
    }

    private static boolean isMessageRace(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return "uq_solution_messages_request".equals(violation.getConstraintName())
                        || "uq_solution_messages_sequence".equals(violation.getConstraintName());
            }
        }
        return false;
    }

    private static SolutionOwner existingOwner(Supplier<Optional<SolutionOwner>> owner) {
        return owner.get().orElseThrow(() -> new ApiException(ErrorCode.SOLUTION_NOT_FOUND));
    }

    private record Snapshot(SolutionFacts facts, SolutionQuestion question) {}
    private static final class ConcurrentUpdate extends RuntimeException {}
}
