package com.wsid.training.dto;

import com.wsid.common.domain.MessageSender;
import com.wsid.training.domain.TrainingMessage;
import com.wsid.training.domain.TrainingPhase;
import com.wsid.training.domain.TrainingSession;
import com.wsid.training.domain.TrainingStatus;
import java.util.List;

/** 새로고침이나 재방문 때 화면을 복구하는 데 쓰는 세션 상태와 지난 대화. */
public record SessionStateResponse(
        long sessionId,
        int stateVersion,
        TrainingPhase phase,
        TrainingStatus status,
        int simulationTurnCount,
        boolean keySceneShown,
        List<HistoryMessage> messages) {
    // TODO(이어하기): 마지막 질문의 선택지(options_json)도 함께 돌려줘요.

    public record HistoryMessage(int sequenceNo, MessageSender sender, String content) {
    }

    public static SessionStateResponse of(TrainingSession session, List<TrainingMessage> messages) {
        List<HistoryMessage> history = messages.stream()
                .map(m -> new HistoryMessage(m.getSequenceNo(), m.getSender(), m.getContent()))
                .toList();
        return new SessionStateResponse(
                session.getId(),
                session.getStateVersion(),
                session.getPhase(),
                session.getStatus(),
                session.getSimulationTurnCount(),
                session.isKeySceneShown(),
                history);
    }
}
