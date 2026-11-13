package com.wsid.training.controller;

import com.wsid.training.dto.*;
import com.wsid.training.service.TrainingService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** TODO(경로 전환): 기존 프론트 이전 후 이 컨트롤러와 서비스의 변환 코드를 삭제해요. */
@RestController
@RequestMapping("/api/training/sessions")
public class LegacyTrainingController {
    private final TrainingService service;
    public LegacyTrainingController(TrainingService service) { this.service = service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public TurnResponse start() { return service.start(); }
    @GetMapping("/active")
    public ResponseEntity<SessionStateResponse> active() {
        return service.findActive().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
    @GetMapping("/{sessionId}")
    public SessionStateResponse state(@PathVariable long sessionId) { return service.getState(sessionId); }
    @PostMapping("/{sessionId}/turns")
    public TurnResponse turn(@PathVariable long sessionId, @Valid @RequestBody SubmitTurnRequest request) {
        return service.submitTurn(sessionId, request);
    }
}
