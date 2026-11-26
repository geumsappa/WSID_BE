package com.wsid.training.dto;

import com.wsid.training.domain.TrainingPhase;
import com.wsid.training.domain.TrainingSession;

/** 훈련 시작과 턴 전송의 공통 응답. 프론트는 stateVersion을 다음 요청에 그대로 돌려보내요. */
public record TurnResponse(
        long sessionId,
        int stateVersion,
        TrainingPhase phase,
        int simulationTurnCount,
        MessageDto message,
        boolean keyScene,
        boolean finished) {

    public static TurnResponse of(TrainingSession session, MessageDto message, boolean keyScene, boolean finished) {
        return new TurnResponse(
                session.getId(),
                session.getStateVersion(),
                session.getPhase(),
                session.getSimulationTurnCount(),
                message,
                keyScene,
                finished);
    }
}
