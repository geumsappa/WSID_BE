package com.wsid.training.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class TrainingRequests {
    private TrainingRequests() {}

    public interface Mutation {
        UUID requestId();
        Integer stateVersion();
    }

    public record Versioned(@NotNull UUID requestId, @NotNull @PositiveOrZero Integer stateVersion)
            implements Mutation {}
    public record Discovery(@NotNull UUID requestId, @NotNull @PositiveOrZero Integer stateVersion,
            @NotBlank @Size(max = 1000) String content) implements Mutation {}
    public record Message(@NotNull UUID requestId, @NotNull @PositiveOrZero Integer stateVersion,
            @NotNull MessageKind inputKind, @Size(max = 1000) String content, @Size(max = 100) String selectedCode)
            implements Mutation {}
    public enum MessageKind { TEXT, CHOICE }
    public record Action(@NotNull UUID requestId, @NotNull @PositiveOrZero Integer stateVersion,
            @NotNull ActionCode actionCode, ActionMeta actionMeta) implements Mutation {}
    /** MOCK_APP_CLICK: 대화 속 가상 앱 설치 링크를 누름. MOCK_TRANSFER: 대화 속 훈련용 계좌로 송금 버튼을 누름. 실제 설치·송금은 없고 눌렀는지만 기록해요. */
    public enum ActionCode { MOCK_PAGE_OPEN, MOCK_PAGE_CLOSE, MOCK_LOGIN_ATTEMPT, OFFICIAL_CHANNEL_CHECK, MOCK_APP_CLICK, MOCK_TRANSFER }
    public record ActionMeta(String templateCode, String screenCode) {}
    public record Finish(@NotNull UUID requestId, @NotNull @PositiveOrZero Integer stateVersion,
            @NotBlank @Size(max = 40) String reason) implements Mutation {}
}
