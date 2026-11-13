package com.wsid.training.domain;

import com.wsid.common.domain.InputKind;
import com.wsid.common.domain.MessageSender;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * training_messages 테이블. 훈련 대화 한 줄이에요.
 * 진행 중에는 이어하기를 위해 보관하고, 리포트를 저장한 뒤에는 원문(content)을 지워요.
 */
@Entity
@Table(name = "training_messages")
public class TrainingMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "training_id", nullable = false, updatable = false)
    private Long trainingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TrainingPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageSender sender;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_kind", nullable = false, length = 15)
    private InputKind inputKind;

    @Column(columnDefinition = "text")
    private String content;

    /** 사용자 메시지면 행동 분류(UserAction), 모의 페이지 이벤트면 이벤트 코드 */
    @Column(name = "action_code", length = 100)
    private String actionCode;

    @Column(name = "action_meta_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String actionMetaJson;

    /** 이 메시지와 함께 보여준 선택지 */
    @Column(name = "options_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String optionsJson;

    /** 턴별 평가 데이터. 현재는 사용하지 않아요. */
    @Column(name = "turn_evaluation_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String turnEvaluationJson;

    /** 세션 안에서의 순서. 1부터 시작해요. */
    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    /** 프론트가 메시지마다 만든 요청 ID. 같은 메시지가 다시 와도 한 번만 반영하는 데 써요. */
    @Column(name = "client_request_id")
    private UUID clientRequestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TrainingMessage() {
        // JPA용
    }

    /** 사용자가 글로 보낸 메시지 */
    public static TrainingMessage fromUser(
            long trainingId,
            TrainingPhase phase,
            int sequenceNo,
            String content,
            UserAction action,
            UUID clientRequestId,
            Instant now) {
        TrainingMessage message = base(trainingId, phase, MessageSender.USER, sequenceNo, content, now);
        message.actionCode = action == null ? null : action.name();
        message.clientRequestId = clientRequestId;
        return message;
    }

    /** AI(가상 인물 또는 안내)가 보낸 메시지 */
    public static TrainingMessage fromAi(long trainingId, TrainingPhase phase, int sequenceNo, String content, Instant now) {
        return base(trainingId, phase, MessageSender.AI, sequenceNo, content, now);
    }

    /** 종료 안내 같은 시스템 메시지 */
    public static TrainingMessage fromSystem(long trainingId, TrainingPhase phase, int sequenceNo, String content, Instant now) {
        return base(trainingId, phase, MessageSender.SYSTEM, sequenceNo, content, now);
    }

    private static TrainingMessage base(
            long trainingId, TrainingPhase phase, MessageSender sender, int sequenceNo, String content, Instant now) {
        TrainingMessage message = new TrainingMessage();
        message.trainingId = trainingId;
        message.phase = phase;
        message.sender = sender;
        message.inputKind = InputKind.TEXT;
        message.sequenceNo = sequenceNo;
        message.content = content;
        message.createdAt = now;
        return message;
    }

    public Long getId() {
        return id;
    }

    public Long getTrainingId() {
        return trainingId;
    }

    public TrainingPhase getPhase() {
        return phase;
    }

    public MessageSender getSender() {
        return sender;
    }

    public InputKind getInputKind() {
        return inputKind;
    }

    public String getContent() {
        return content;
    }

    public String getActionCode() {
        return actionCode;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public UUID getClientRequestId() {
        return clientRequestId;
    }
}
