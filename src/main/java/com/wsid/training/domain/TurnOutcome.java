package com.wsid.training.domain;

/**
 * 한 턴을 적용한 결과. 서비스 계층은 이 값으로 어떤 문구를 내보낼지 골라요.
 *
 * @param phase      적용 후 단계
 * @param keyScene   이번 턴에 핵심 장면을 보여주는지
 * @param textSource AI 대사를 쓸지, 검토된 문구를 쓸지
 * @param finished   이번 턴으로 훈련이 끝났는지
 */
public record TurnOutcome(TrainingPhase phase, boolean keyScene, TextSource textSource, boolean finished) {
}
