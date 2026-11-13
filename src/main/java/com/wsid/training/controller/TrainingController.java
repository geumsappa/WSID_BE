package com.wsid.training.controller;

import com.wsid.training.dto.SessionStateResponse;
import com.wsid.training.dto.SubmitTurnRequest;
import com.wsid.training.dto.TurnResponse;
import com.wsid.training.service.TrainingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 요청 검증과 응답 변환만 해요. 규칙 판단은 하지 않아요. */
@RestController
@RequestMapping("/api/trainings")
public class TrainingController {

    private final TrainingService trainingService;

    public TrainingController(TrainingService trainingService) {
        this.trainingService = trainingService;
    }

    /** 훈련 시작 (SIM-001) */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TurnResponse start() {
        return trainingService.start();
    }

    /** 홈의 '이어서 하기'. 진행 중인 훈련이 없으면 204 */
    @GetMapping("/active")
    public ResponseEntity<SessionStateResponse> findActive() {
        return trainingService.findActive()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 훈련 재개: 상태와 지난 대화 */
    @GetMapping("/{sessionId}")
    public SessionStateResponse getState(@PathVariable long sessionId) {
        return trainingService.getState(sessionId);
    }

    @PostMapping("/{sessionId}/discovery/messages")
    public com.wsid.training.dto.DiscoveryResponse submitDiscovery(@PathVariable long sessionId, @Valid @RequestBody SubmitTurnRequest request) {
        return trainingService.submitDiscovery(sessionId, request);
    }

    @PostMapping("/{sessionId}/messages")
    public TurnResponse submitSimulationMessage(@PathVariable long sessionId, @Valid @RequestBody SubmitTurnRequest request) {
        return trainingService.submitSimulationMessage(sessionId, request);
    }

}
