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
    /** quickStart: 홈의 '바로 체험하기'로 고른 상황이면 true. 추가 질문 없이 바로 시나리오를 만들 수 있게 해요 */
    public record Discovery(@NotNull UUID requestId, @NotNull @PositiveOrZero Integer stateVersion,
            @NotBlank @Size(max = 1000) String content, Boolean quickStart) implements Mutation {
        public Discovery(UUID requestId, Integer stateVersion, String content) {
            this(requestId, stateVersion, content, null);
        }
    }
    public record Message(@NotNull UUID requestId, @NotNull @PositiveOrZero Integer stateVersion,
            @NotNull MessageKind inputKind, @Size(max = 1000) String content, @Size(max = 100) String selectedCode)
            implements Mutation {}
    public enum MessageKind { TEXT, CHOICE }
    public record Action(@NotNull UUID requestId, @NotNull @PositiveOrZero Integer stateVersion,
            @NotNull ActionCode actionCode, ActionMeta actionMeta) implements Mutation {}
    /** MOCK_APP_CLICK: 대화 속 가상 앱 설치 링크를 누름. 실제 설치는 없고 눌렀는지만 기록해요. */
    public enum ActionCode { MOCK_PAGE_OPEN, MOCK_PAGE_CLOSE, MOCK_LOGIN_ATTEMPT, OFFICIAL_CHANNEL_CHECK, MOCK_APP_CLICK }
    public record ActionMeta(String templateCode, String screenCode) {}
    public record Finish(@NotNull UUID requestId, @NotNull @PositiveOrZero Integer stateVersion,
            @NotBlank @Size(max = 40) String reason) implements Mutation {}
}
