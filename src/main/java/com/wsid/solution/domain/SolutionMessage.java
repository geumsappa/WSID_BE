package com.wsid.solution.domain;

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
 * solution_messages 테이블.
 * safe_content에는 민감정보를 가린 내용만 저장해요. 피해 설명 원문은 저장하지 않아요.
 */
@Entity
@Table(name = "solution_messages")
public class SolutionMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "solution_id", nullable = false, updatable = false)
    private Long solutionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageSender sender;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_kind", nullable = false, length = 15)
    private InputKind inputKind;

    @Column(name = "safe_content", columnDefinition = "text")
    private String safeContent;

    /** 사용자가 고른 선택지 코드 */
    @Column(name = "selected_code", length = 100)
    private String selectedCode;

    /** 이 메시지에서 안내한 카드·근거 문서 코드 목록 */
    @Column(name = "reference_codes_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String referenceCodesJson;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "client_request_id")
    private UUID clientRequestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SolutionMessage() {
        // JPA용
    }

    public static SolutionMessage of(
            long solutionId,
            MessageSender sender,
            InputKind inputKind,
            int sequenceNo,
            String safeContent,
            UUID clientRequestId,
            Instant now) {
        SolutionMessage message = new SolutionMessage();
        message.solutionId = solutionId;
        message.sender = sender;
        message.inputKind = inputKind;
        message.sequenceNo = sequenceNo;
        message.safeContent = safeContent;
        message.clientRequestId = clientRequestId;
        message.createdAt = now;
        return message;
    }

    public Long getId() {
        return id;
    }

    public Long getSolutionId() {
        return solutionId;
    }

    public MessageSender getSender() {
        return sender;
    }

    public InputKind getInputKind() {
        return inputKind;
    }

    public String getSafeContent() {
        return safeContent;
    }

    public String getSelectedCode() {
        return selectedCode;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }
}
