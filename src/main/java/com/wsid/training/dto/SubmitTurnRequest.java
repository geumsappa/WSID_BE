package com.wsid.training.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * @param requestId    프론트가 메시지마다 새로 만드는 UUID. 같은 메시지가 다시 와도 한 번만 반영해요.
 * @param stateVersion 프론트가 마지막으로 받은 세션 버전
 * @param message      사용자 답변
 */
public record SubmitTurnRequest(
        @NotNull UUID requestId,
        @NotNull Integer stateVersion,
        @NotBlank @Size(max = 1000) String message) {
}
