package com.wsid.training.dto;

import com.wsid.common.domain.MessageSender;
import com.wsid.training.domain.TrainingPhase;
import com.wsid.training.domain.TrainingStatus;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class TrainingResponses {
    private TrainingResponses() {}

    public static String phase(TrainingPhase phase) {
        return phase == TrainingPhase.REPORT ? "RESULT" : phase.name();
    }

    public record FirstMessage(MessageSender sender, String content) {}
    public record Created(long trainingId, String phase, TrainingStatus status, int stateVersion,
            FirstMessage firstMessage) {}
    public record Active(long trainingId, String phase, TrainingStatus status, Instant lastActivityAt) {}
    public record HistoryMessage(long id, MessageSender sender, String inputKind, String content, String phase,
            MockAttachment mockAttachment) {
        public HistoryMessage(long id, MessageSender sender, String inputKind, String content, String phase) {
            this(id, sender, inputKind, content, phase, null);
        }
    }
    public record Detail(long trainingId, String phase, TrainingStatus status, int stateVersion, int turnCount,
            List<HistoryMessage> messages, JsonNode availableOptions, MockPage mockPage, ScenarioSummary scenario,
            boolean canGenerateScenario, boolean shouldFinish, boolean mockPageSubmitted, boolean mockAppClicked) {
        public Detail(long trainingId, String phase, TrainingStatus status, int stateVersion, int turnCount,
                List<HistoryMessage> messages, JsonNode availableOptions, MockPage mockPage, ScenarioSummary scenario,
                boolean canGenerateScenario, boolean shouldFinish, boolean mockPageSubmitted) {
            this(trainingId, phase, status, stateVersion, turnCount, messages, availableOptions, mockPage, scenario,
                    canGenerateScenario, shouldFinish, mockPageSubmitted, false);
        }
    }
    public record MockAttachment(String type, String title) {}
    public record Option(String code, String label, MockAttachment mockAttachment) {
        public Option(String code, String label) { this(code, label, null); }
    }
    public record Reply(MessageSender sender, String content, List<Option> options) {}
    public record Discovery(FirstMessage reply, int discoveryTurnCount, boolean canGenerateScenario, int stateVersion) {}
    /** channel: 대화 화면 모양을 고르는 코드 (SMS, MESSENGER, MARKET, EMAIL, SNS). 모르면 null */
    public record ScenarioSummary(String title, String description, String senderName, String organizationName,
            String channel) {
        public ScenarioSummary(String title, String description) { this(title, description, null, null, null); }
        public ScenarioSummary(String title, String description, String senderName, String organizationName) {
            this(title, description, senderName, organizationName, null);
        }
    }
    public record Scenario(long trainingId, String phase, String fraudTypeCode, ScenarioSummary scenario,
            String mockPageTemplateCode, int stateVersion) {}
    public record Started(String phase, FirstMessage firstMessage, int stateVersion, List<Option> availableOptions, MockPage mockPage) {
        public Started(String phase, FirstMessage firstMessage, int stateVersion) {
            this(phase, firstMessage, stateVersion, List.of(), null);
        }
        public Started(String phase, FirstMessage firstMessage, int stateVersion, List<Option> availableOptions) {
            this(phase, firstMessage, stateVersion, availableOptions, null);
        }
    }
    /** maskedInput: 이름·주소 등을 가린 내 메시지 (가릴 게 없으면 null). 프론트는 내 말풍선을 이 값으로 바꿔 보여 줘요 */
    public record Message(Reply reply, List<String> recognizedActions, int turnCount, boolean shouldFinish,
            MockPage mockPage, int stateVersion, String maskedInput) {}
    public record MockPage(String templateCode, String title, String institutionName, String screenCode,
            boolean isSimulation) {
        public static MockPage bankNotice() {
            return new MockPage("BANK_NOTICE", "가상 금융 안내", "가상금융센터", "LOGIN_CHECK", true);
        }
    }
    public record Action(boolean recorded, int stateVersion) {}
    public record Finished(long trainingId, TrainingStatus status, String phase, long reportId) {}
}
